#!/usr/bin/env python3
"""Download a Maven dependency closure via curl into a local Maven repo dir.

The VM's sandbox blocks JVM (Gradle) TCP, so Gradle cannot download from
remote repos itself. curl works, so this script fetches every POM + artifact
the build needs, and the build then runs --offline against the local repo.

Strategy: BFS over POMs. Extra artifacts are harmless; version conflicts are
left to Gradle (all encountered versions are downloaded). Exclusions and
optional flags are ignored (downloading extra is safe). Scopes: compile,
provided, runtime (test/system skipped). Import-scope BOMs are followed.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
    "https://plugins.gradle.org/m2",
]
OUT = os.path.expanduser("~/workspace/new-app/android/local-repo")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SKIP_SCOPES = {"test", "system"}


def curl_fetch(url, dest):
    for _ in range(3):
        r = subprocess.run(
            ["curl", "-sS", "-L", "-o", dest, "-w", "%{http_code}", "--max-time", "60", url],
            capture_output=True, text=True,
        )
        if r.stdout.strip() == "200" and os.path.exists(dest) and os.path.getsize(dest) > 0:
            return True
        if os.path.exists(dest):
            os.remove(dest)
    return False


def gpath(g):
    return g.replace(".", "/")


def pom_dest(g, a, v):
    return f"{OUT}/{gpath(g)}/{a}/{v}/{a}-{v}.pom"


def fetch_pom(g, a, v):
    dest = pom_dest(g, a, v)
    if os.path.exists(dest):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    rel = f"{gpath(g)}/{a}/{v}/{a}-{v}.pom"
    for repo in REPOS:
        if curl_fetch(f"{repo}/{rel}", dest):
            return dest
    return None


def fetch_artifact(g, a, v, packaging):
    if packaging == "pom":
        return None
    if packaging == "aar":
        exts = ["aar", "jar"]
    elif packaging == "jar":
        exts = ["jar"]
    else:
        # e.g. tar.gz (GraalVM native libs) — the packaging IS the extension;
        # bundle (OSGi) and friends are jar files under another name.
        exts = [packaging, "jar"] if packaging != "jar" else ["jar"]
    for ext in exts:
        dest = f"{OUT}/{gpath(g)}/{a}/{v}/{a}-{v}.{ext}"
        if os.path.exists(dest):
            return dest
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        rel = f"{gpath(g)}/{a}/{v}/{a}-{v}.{ext}"
        for repo in REPOS:
            if curl_fetch(f"{repo}/{rel}", dest):
                return dest
    return None


def txt(el, tag, default=""):
    if el is None:
        return default
    v = el.findtext(tag, "")
    return v.strip() if v else default


def strip_ns(root):
    """Some POMs (e.g. Bouncy Castle) declare no XML namespace; others do.
    Normalize all tags to local names so one parser handles both."""
    for el in root.iter():
        if "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return root


def substitute(text, props):
    if not text:
        return text
    for _ in range(10):
        new = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), text)
        if new == text:
            return new
        text = new
    return text


def parse_pom(path):
    """Returns (props, dep_mgmt, parent_ref, packaging, deps, relocation).
    deps: list of (group, artifact, version_or_None, scope, type, optional)."""
    root = strip_ns(ET.parse(path).getroot())
    parent_ref = None
    parent = root.find("parent")
    if parent is not None:
        parent_ref = (txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version"))
    props = {}
    for p in root.findall("properties/*"):
        tag = p.tag.split("}")[-1]
        props[tag] = (p.text or "").strip()
    # project.* فقط وقتی خود POM مقدار دارد ست می‌شود، تا مقدار والد
    # (در زنجیره‌ی merge) با رشته‌ی خالی رونویسی نشود.
    _gv, _vv, _av = txt(root, "groupId"), txt(root, "version"), txt(root, "artifactId")
    if _gv:
        props["project.groupId"] = _gv
    if _vv:
        props["project.version"] = _vv
    if _av:
        props["project.artifactId"] = _av
    packaging = txt(root, "packaging", "jar") or "jar"
    dep_mgmt = {}
    imports = []
    dm = root.find("dependencyManagement/dependencies")
    if dm is not None:
        for d in dm.findall("dependency"):
            g, a, v = txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version")
            scope, typ = txt(d, "scope"), txt(d, "type", "jar")
            if scope == "import" and typ == "pom":
                imports.append((g, a, v))
            elif v:
                dep_mgmt[(g, a)] = v
    deps = []
    for d in root.findall("dependencies/dependency"):
        deps.append((
            txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version") or None,
            txt(d, "scope"), txt(d, "type", "jar"),
            txt(d, "optional") == "true",
        ))
    reloc = None
    rel = root.find("distributionManagement/relocation")
    if rel is not None:
        reloc = (txt(rel, "groupId"), txt(rel, "artifactId"), txt(rel, "version"))
    return props, dep_mgmt, parent_ref, packaging, deps, reloc, imports


def resolve_version(v, props, dep_mgmt_chain):
    if v:
        v = substitute(v, props)
        if not v.startswith("${"):
            return v
    return None


def ver_key(v):
    # crude ordering: numeric parts, then qualifier rank
    parts = re.split(r"[.\-]", v)
    out = []
    for p in parts:
        if p.isdigit():
            out.append((0, int(p)))
        else:
            out.append((1, p))
    return out


def pick_from_metadata(g, a, spec):
    """Resolve a range / '+' spec. Exact [V] needs no metadata."""
    # exact version in brackets, e.g. [1.6.8] (common in AndroidX POMs)
    m = re.match(r"^\[([^,\]]+)\]$", spec)
    if m:
        return m.group(1).strip()
    dest = f"/tmp/meta-{g}-{a}.xml"
    rel = f"{gpath(g)}/{a}/maven-metadata.xml"
    for repo in REPOS:
        if curl_fetch(f"{repo}/{rel}", dest):
            break
    else:
        return None
    try:
        root = ET.parse(dest).getroot()
    except Exception:
        return None
    # maven-metadata.xml is conventionally namespace-less; fall back to the
    # POM namespace if a repo ever serves it namespaced.
    versions = [e.text for e in root.findall(".//version") if e.text]
    if not versions:
        versions = [e.text for e in root.findall(".//m:version", NS) if e.text]
    if not versions:
        return None
    if spec.endswith("+"):
        prefix = spec[:-1]
        cands = [v for v in versions if v.startswith(prefix)]
        return max(cands, key=ver_key) if cands else None
    # exact version in brackets, e.g. [2.8.0] (common in AndroidX POMs)
    m = re.match(r"^\[([^,\]]+)\]$", spec)
    if m:
        want = m.group(1).strip()
        return want if want in versions else want
    m = re.match(r"[\[(]([^,]*),([^)\]]*)[)\]]", spec)
    if m:
        lo, hi = m.group(1).strip(), m.group(2).strip()
        cands = versions
        if lo:
            cands = [v for v in cands if ver_key(v) >= ver_key(lo)]
        if hi:
            cands = [v for v in cands if ver_key(v) < ver_key(hi)]
        return max(cands, key=ver_key) if cands else None
    return None


def main():
    seeds = [
        # plugin markers (their POMs depend on the real plugin artifacts)
        ("com.android.application", "com.android.application.gradle.plugin", "8.5.2"),
        ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin", "1.9.24"),
        # compose compiler extension (AGP composeOptions, روش قدیمیِ سازگار با Kotlin 1.9.x)
        ("androidx.compose.compiler", "compiler", "1.5.14"),
        # BOM خود compose (platform در app/build.gradle.kts) — نسخه‌های
        # بدون‌ورژن material3/ui/ui-tooling-preview از همین‌جا می‌آیند.
        ("androidx.compose", "compose-bom", "2024.06.00"),
        # app libraries (compose versions resolved from BOM 2024.06.00)
        ("androidx.compose.material3", "material3", "1.2.1"),
        ("androidx.compose.ui", "ui", "1.6.8"),
        ("androidx.compose.ui", "ui-tooling-preview", "1.6.8"),
        ("androidx.activity", "activity-compose", "1.9.0"),
        ("androidx.navigation", "navigation-compose", "2.8.0"),
        ("androidx.core", "core-ktx", "1.13.1"),
        ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.2"),
        ("com.squareup.retrofit2", "retrofit", "2.11.0"),
        ("com.squareup.retrofit2", "converter-gson", "2.11.0"),
        ("com.squareup.okhttp3", "okhttp", "4.12.0"),
        ("io.coil-kt", "coil-compose", "2.7.0"),
        ("com.airbnb.android", "lottie-compose", "6.5.0"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24"),
        # Kotlin تعبیه‌شده‌ی خود Gradle 8.9 (برای کامپایل اسکریپت‌های kts)
        # نسخه‌ی 1.9.23 است و در classpath روت لازم می‌شود.
        ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.9.23"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.23"),
    ]
    queue = deque(seeds)
    seen = set(seeds)
    unresolved = []
    pom_cache = {}

    def load_pom_chain(g, a, v):
        """Load a POM + its parents. Returns (merged_props, merged_dep_mgmt, packaging, deps, reloc)."""
        chain = []
        cur = (g, a, v)
        for _ in range(20):
            path = fetch_pom(*cur)
            if not path:
                return None
            if path in pom_cache:
                parsed = pom_cache[path]
            else:
                parsed = parse_pom(path)
                pom_cache[path] = parsed
            chain.append((cur, parsed))
            parent = parsed[2]
            if not parent or parent == cur:
                break
            pg, pa, pv = parent
            pv = substitute(pv, {}) if pv else pv
            cur = (pg, pa, pv)
        # merge: nearest POM wins for props/dep_mgmt
        props, dep_mgmt = {}, {}
        for _, parsed in reversed(chain):
            props.update(parsed[0])
            dep_mgmt.update(parsed[1])
        # import-scope BOMs: from EVERY POM in the chain (an artifact's
        # parent often imports the BOM Gradle needs to parse the model),
        # recursively — a BOM may itself import other BOMs.
        def collect_imports(parsed, seen_boms):
            for ig, ia, iv in parsed[6]:
                iv = substitute(iv, props)
                if not iv or iv.startswith("${"):
                    continue
                key = (ig, ia, iv)
                if key in seen_boms:
                    continue
                seen_boms.add(key)
                ipath = fetch_pom(ig, ia, iv)
                if not ipath:
                    continue
                iparsed = parse_pom(ipath)
                for k, val in iparsed[1].items():
                    dep_mgmt.setdefault(k, val)
                collect_imports(iparsed, seen_boms)

        seen_boms = set()
        for _, parsed in chain:
            collect_imports(parsed, seen_boms)
        head = chain[0][1]
        return props, dep_mgmt, head[3], head[4], head[5]

    # نقشه‌ی BOM کامپوز: platform() در Gradle نسخه‌ی BOM را بر نسخه‌ی
    # اعلام‌شده در POM هر وابستگی ترجیح می‌دهد (alignment). پس علاوه بر
    # نسخه‌ی POM، نسخه‌ی BOM را هم دانلود می‌کنیم تا هر دو در مخزن باشد.
    bom_map = {}
    bom_loaded = load_pom_chain("androidx.compose", "compose-bom", "2024.06.00")
    if bom_loaded:
        _bprops, _bmgmt = bom_loaded[0], bom_loaded[1]
        for _k, _v in _bmgmt.items():
            bom_map[_k] = substitute(_v, _bprops)
        print(f"BOM map: {len(bom_map)} entries")

    processed = 0
    while queue:
        g, a, v = queue.popleft()
        loaded = load_pom_chain(g, a, v)
        if not loaded:
            print(f"MISSING POM: {g}:{a}:{v}")
            unresolved.append((g, a, v))
            continue
        props, dep_mgmt, packaging, deps, reloc = loaded
        # own version may be a property of the parent chain; fill project.* fallbacks
        props.setdefault("project.version", v)
        if reloc and all(reloc):
            rg, ra, rv = (substitute(x, props) for x in reloc)
            if (rg, ra, rv) not in seen:
                seen.add((rg, ra, rv))
                queue.append((rg, ra, rv))
        if packaging != "pom":
            got = fetch_artifact(g, a, v, packaging)
            if not got:
                print(f"MISSING ARTIFACT: {g}:{a}:{v} (packaging {packaging})")
                unresolved.append((g, a, v))
        for dg, da, dv, scope, typ, optional in deps:
            if scope in SKIP_SCOPES or optional:
                continue
            dg = substitute(dg, props)
            da = substitute(da, props)
            ver = None
            if dv:
                ver = substitute(dv, props)
                if ver.startswith("${"):
                    ver = dep_mgmt.get((dg, da))
                    ver = substitute(ver, props) if ver else None
            else:
                ver = dep_mgmt.get((dg, da))
                ver = substitute(ver, props) if ver else None
            if not ver or ver.startswith("${"):
                print(f"UNRESOLVED VERSION: {dg}:{da} (from {g}:{a}:{v})")
                unresolved.append((dg, da, dv or "?"))
                continue
            if ver.startswith("[") or ver.startswith("(") or ver.endswith("+"):
                ver = pick_from_metadata(dg, da, ver)
                if not ver:
                    print(f"UNRESOLVED RANGE: {dg}:{da}")
                    continue
            key = (dg, da, ver)
            if key not in seen:
                seen.add(key)
                queue.append(key)
            # نسخه‌ی BOM (alignment پلتفرم) را هم کنار نسخه‌ی POM بگذار.
            bver = bom_map.get((dg, da))
            if bver and bver != ver:
                bkey = (dg, da, bver)
                if bkey not in seen:
                    seen.add(bkey)
                    queue.append(bkey)
        processed += 1
        if processed % 50 == 0:
            print(f"... {processed} POMs processed, queue={len(queue)}")

    print(f"DONE: {processed} artifacts processed.")
    if unresolved:
        print(f"UNRESOLVED ({len(unresolved)}):")
        for u in sorted(set(unresolved)):
            print("  ", u)


if __name__ == "__main__":
    main()
