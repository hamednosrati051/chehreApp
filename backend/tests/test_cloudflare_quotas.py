"""Cloudflare quota display: a 429 exhausts only the model that got it.

Regression (2026-11-21): model 9 showed 0 remaining though it never got
a 429 — _cloudflare_quotas used ONE global last_429 for all models, so
model 8's 429 marked every model exhausted that hadn't succeeded since
it. The last 429 is now tracked per model.

Runs (from repo root):
    python3 backend/tests/test_cloudflare_quotas.py

Standalone, SQLite in memory (TestConfig), no external services.
"""

import datetime
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import AppGeneration, AppModel, AppUser  # noqa: E402
from app.routes import _cloudflare_quotas  # noqa: E402

PASSED = 0
FAILED = 0


def check(name, cond, extra=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ok: {name}")
    else:
        FAILED += 1
        print(f"  FAIL: {name} {extra}")


class TestConfig(Config):
    SQLALCHEMY_DATABASE_URI = "sqlite://"
    TESTING = True


_ERR_429 = (
    'provider http 429: {"errors": [{"message": "AiError: you have used '
    'up your daily free allocation"}]}'
)


def _model(name):
    m = AppModel(
        kind="image",
        name=name,
        slug=name,
        provider="cloudflare",
        provider_type="cloudflare_workers_ai",
        provider_model_name="@cf/black-forest-labs/flux-2-klein-4b",
        capability="edit_image",
        pricing_type="image_count",
        is_active=True,
    )
    db.session.add(m)
    return m


def _gen(user_id, model_id, status, created_at, error=None):
    g = AppGeneration(
        user_id=user_id,
        kind="image",
        model_id=model_id,
        status=status,
        coin_cost=10,
        error_message=error,
        created_at=created_at,
    )
    db.session.add(g)
    return g


def main():
    app = create_app(TestConfig)
    with app.app_context():
        db.create_all()
        u = AppUser()
        db.session.add(u)
        db.session.flush()

        a = _model("cf-a")  # the model that got the 429 (model 8 case)
        b = _model("cf-b")  # successes BEFORE a's 429 (model 9 case)
        c = _model("cf-c")  # success AFTER a's 429 (model 7 case)
        d = _model("cf-d")  # unused today (Nov 19 fix: never exhausted)
        db.session.flush()

        now = datetime.datetime.utcnow()
        day = now.replace(hour=0, minute=0, second=0, microsecond=0)
        t = lambda h, m=0: day + datetime.timedelta(hours=h, minutes=m)

        # A: two 429s today, no success since.
        _gen(u.id, a.id, "failed", t(2, 13), _ERR_429)
        _gen(u.id, a.id, "failed", t(2, 15), _ERR_429)
        # B: two successes before A's 429 + one 429 YESTERDAY (must not count).
        _gen(u.id, b.id, "done", t(0, 35))
        _gen(u.id, b.id, "done", t(0, 56))
        _gen(u.id, b.id, "failed", day - datetime.timedelta(hours=1), _ERR_429)
        # C: one success after A's 429.
        _gen(u.id, c.id, "done", t(6, 4))
        db.session.commit()

        q = _cloudflare_quotas()
        check("A exhausted by its own 429",
              q[a.id]["exhausted"] is True and q[a.id]["remaining_today"] == 0
              and q[a.id]["done_today"] == 0, f"got {q[a.id]}")
        check("B NOT exhausted by A's 429 (own successes predate it)",
              q[b.id]["exhausted"] is False and q[b.id]["remaining_today"] == 91
              and q[b.id]["done_today"] == 2, f"got {q[b.id]}")
        check("C NOT exhausted (success after A's 429)",
              q[c.id]["exhausted"] is False and q[c.id]["remaining_today"] == 92
              and q[c.id]["done_today"] == 1, f"got {q[c.id]}")
        check("D unused today is NOT exhausted",
              q[d.id]["exhausted"] is False and q[d.id]["remaining_today"] == 93
              and q[d.id]["done_today"] == 0, f"got {q[d.id]}")

        # A recovers: a success after its own 429 clears the flag.
        _gen(u.id, a.id, "done", t(7, 0))
        db.session.commit()
        q = _cloudflare_quotas()
        check("A recovers after a success past its own 429",
              q[a.id]["exhausted"] is False and q[a.id]["remaining_today"] == 92
              and q[a.id]["done_today"] == 1, f"got {q[a.id]}")

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
