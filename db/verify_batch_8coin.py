"""Verify the 8-coin batch. Prints values/status only, never tokens."""

import json
import os
import urllib.request

B = "http://127.0.0.1:5001/api/v1"
T = os.environ["APP_ADMIN_TOKEN"]


def call(method, path, body=None, admin=True):
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


s, packs = call("GET", "/admin/packs")
print("admin-packs:", s, [(p["name"], p["coins"], p["price_toman"],
                            p["pack_type"]) for p in packs])
s, pub = call("GET", "/coins/packs")
print("public-packs:", s, [(p["name"], p["coins"], p["price_toman"],
                             p.get("pack_type")) for p in pub])
s, worlds = call("GET", "/admin/worlds")
print("worlds:", s, [(w["name"], w["coin_price"]) for w in worlds])
s, settings = call("GET", "/admin/settings")
print("settings:", s, [(r["key"], r["value"]) for r in settings])
s, _ = call("GET", "/admin/pricing")
print("pricing-endpoint:", s, "(want 404)")
s, _ = call("PATCH", "/admin/packs/1", {"pack_type": "test"})
print("pack_type-set:", s)
s, packs = call("GET", "/admin/packs")
print("test-pack-type:", [p["pack_type"] for p in packs if p["id"] == 1])
s, _ = call("PATCH", "/admin/packs/1", {"pack_type": None})
print("pack_type-restore:", s)
s, packs = call("GET", "/admin/packs")
print("test-pack-type-after:", [p["pack_type"] for p in packs if p["id"] == 1])
s, models = call("GET", "/admin/models")
print("models:", s, [(m["name"], m["capability"]) for m in models])
