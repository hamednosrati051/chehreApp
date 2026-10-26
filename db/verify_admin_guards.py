"""Verify the new admin guards. Prints status codes only, never tokens."""

import json
import os
import urllib.request

B = "http://127.0.0.1:5001/api/v1"
T = os.environ["APP_ADMIN_TOKEN"]


def call(method, path, body=None):
    req = urllib.request.Request(
        B + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"X-Admin-Token": T, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def get(path):
    return call("GET", path)[1]


packs = get("/admin/packs")
cats = get("/admin/categories")
worlds = get("/admin/worlds")
print("packs:", [(p["id"], p["bazaar_sku"]) for p in packs])
print("cats:", [(c["id"], c["slug"]) for c in cats])
print("worlds:", [(w["id"], w["slug"], w["coin_price"]) for w in worlds])

s, _ = call("PATCH", f"/admin/packs/{packs[0]['id']}",
             {"bazaar_sku": packs[1]["bazaar_sku"]})
print("pack-dup-sku:", s)
s, _ = call("PATCH", f"/admin/categories/{cats[0]['id']}",
             {"slug": cats[1]["slug"]})
print("cat-dup-slug:", s)
other_slug = [w["slug"] for w in worlds if w["id"] != worlds[0]["id"]][0]
s, _ = call("PATCH", f"/admin/worlds/{worlds[0]['id']}",
             {"slug": other_slug})
print("world-dup-slug:", s)
s, _ = call("POST", "/admin/users/999999/coin-adjust",
             {"delta": 10, "reason": "test"})
print("coin-adjust-missing-user:", s)
s, body = call("GET", "/admin/models")
print("models-list:", s, "count:", len(body))
s, body = call("GET", "/admin/settings")
print("settings:", s, [(r["key"], r["value"]) for r in body
                       if r["key"] == "image_coin_price"])
