"""ChehreSaz v14: فیلد in_gallery در GET /generations/mine.

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v14_in_gallery.py

سبک مثل tests/test_cleanup.py قدیمی: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
هیچ سرویس خارجی صدا زده نمی‌شود (OTP verify پیامک نمی‌فرستد؛
login فقط برای گرفتن توکن استفاده می‌شود).
"""

import datetime
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from werkzeug.security import generate_password_hash  # noqa: E402

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppGallery,
    AppGeneration,
    AppModel,
    AppOtpCode,
    AppUser,
    AppWorld,
)

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


# SQLite به BIGINT PRIMARY KEY مقدار خودکار نمی‌دهد (فقط INTEGER
# PRIMARY KEY جانشین ROWID است)؛ مثل test_cleanup قدیمی، idها را
# دستی می‌دهیم. در MySQL واقعی autoincrement کار می‌کند.
_NEXT_ID = [0]


def next_id():
    _NEXT_ID[0] += 1
    return _NEXT_ID[0]


class TestConfig(Config):
    SQLALCHEMY_DATABASE_URI = "sqlite://"
    TESTING = True


def _otp_login(client, mobile, code="123456"):
    """یک ردیف OTP می‌سازد و با verify واقعی وارد می‌شود؛ توکن برمی‌گردد.

    کاربر باید از قبل ساخته شده باشد (مسیر کاربر موجود) تا verify
    INSERT نکند — همان محدودیت SQLite/BIGINT بالا.
    """
    db.session.add(
        AppOtpCode(
            id=next_id(),
            mobile=mobile,
            code_hash=generate_password_hash(code),
            expires_at=datetime.datetime.utcnow() + datetime.timedelta(minutes=10),
        )
    )
    db.session.commit()
    r = client.post(
        "/api/v1/auth/otp/verify", json={"mobile": mobile, "code": code}
    )
    assert r.status_code == 200, f"otp verify failed: {r.status_code} {r.data!r}"
    return r.get_json()["token"]


def _make_user(mobile, free_image_credits=1):
    """کاربر موجود، مثل قبل از v14 (بدون هیچ پرچم هدیه‌ای)."""
    u = AppUser(
        id=next_id(),
        mobile=mobile,
        free_image_credits=free_image_credits,
        free_voice_credits=0,
    )
    db.session.add(u)
    db.session.commit()
    return u


def _make_gen(user_id, model_id, world_id, output_url):
    gen = AppGeneration(
        id=next_id(),
        user_id=user_id,
        kind="image",
        world_id=world_id,
        model_id=model_id,
        input_url="https://example.invalid/in.jpg",
        output_url=output_url,
        status="done",
        coin_cost=10,
    )
    db.session.add(gen)
    db.session.commit()
    return gen


def main():
    app = create_app(TestConfig)
    with app.app_context():
        db.create_all()
        client = app.test_client()

        # --- مدل و دنیای حداقلی برای ساختن generation ---
        model = AppModel(
            id=next_id(),
            kind="image",
            name="test-model",
            provider="test",
            provider_type="openai_compat",
            capability="edit_image",
            is_active=True,
        )
        db.session.add(model)
        db.session.commit()
        world = AppWorld(
            id=next_id(),
            name="test-world",
            slug="test-world",
            hidden_prompt="test",
            model_id=model.id,
            coin_price=10,
            is_active=True,
        )
        db.session.add(world)
        db.session.commit()

        # --- کاربر ۱: ورود واقعی با OTP (رگرسیون: هدیه‌ی ورود همان ۱ است) ---
        u1 = _make_user("09120000001")
        token1 = _otp_login(client, "09120000001")
        db.session.expire_all()
        u1 = AppUser.query.filter_by(mobile="09120000001").first()
        check("otp login: existing user keeps entry gift of exactly 1 "
              "(no extra login gift)",
              u1.free_image_credits == 1,
              f"got {u1.free_image_credits}")
        check("revert guard: no login_gift_granted column on AppUser",
              "login_gift_granted" not in AppUser.__table__.columns)

        # --- ۴ تولید برای کاربر ۱: pending / approved / rejected / بدون ردیف ---
        g_pending = _make_gen(u1.id, model.id, world.id, "https://example.invalid/1.jpg")
        g_approved = _make_gen(u1.id, model.id, world.id, "https://example.invalid/2.jpg")
        g_rejected = _make_gen(u1.id, model.id, world.id, "https://example.invalid/3.jpg")
        g_none = _make_gen(u1.id, model.id, world.id, "https://example.invalid/4.jpg")
        for gen, status in (
            (g_pending, "pending"),
            (g_approved, "approved"),
            (g_rejected, "rejected"),
        ):
            db.session.add(
                AppGallery(
                    id=next_id(),
                    user_id=u1.id,
                    generation_id=gen.id,
                    image_url=gen.output_url,
                    status=status,
                )
            )
        db.session.commit()

        # --- کاربر ۲: یک تولید با ردیف گالری (نباید به لیست کاربر ۱ نشت کند) ---
        u2 = _make_user("09120000002")
        token2 = _otp_login(client, "09120000002")
        g_other = _make_gen(u2.id, model.id, world.id, "https://example.invalid/9.jpg")
        db.session.add(
            AppGallery(
                id=next_id(),
                user_id=u2.id,
                generation_id=g_other.id,
                image_url=g_other.output_url,
                status="pending",
            )
        )
        db.session.commit()

        # --- GET /generations/mine برای کاربر ۱ ---
        r = client.get(
            "/api/v1/generations/mine",
            headers={"Authorization": f"Bearer {token1}"},
        )
        check("mine: 200", r.status_code == 200, f"got {r.status_code}")
        items = r.get_json()
        by_id = {it["id"]: it for it in items}
        check("mine: only own 4 generations listed",
              set(by_id) == {g_pending.id, g_approved.id, g_rejected.id, g_none.id},
              f"got {sorted(by_id)}")
        check("mine: pending gallery -> in_gallery True",
              by_id[g_pending.id].get("in_gallery") is True)
        check("mine: approved gallery -> in_gallery True",
              by_id[g_approved.id].get("in_gallery") is True)
        check("mine: rejected gallery -> in_gallery True",
              by_id[g_rejected.id].get("in_gallery") is True)
        check("mine: no gallery row -> in_gallery False",
              by_id[g_none.id].get("in_gallery") is False)

        # --- شکل پاسخ عوض نشده، جز فیلد افزوده‌شده ---
        expected_keys = {
            "id", "kind", "status", "output_url", "world_id", "world_name",
            "is_free", "in_gallery", "created_at_fa",
        }
        check("mine: per-item keys exactly the old set + in_gallery",
              all(set(it) == expected_keys for it in items),
              f"got {[sorted(it) for it in items]}")
        check("mine: is_free still present",
              all("is_free" in it for it in items))

        # --- کاربر ۲: فقط تولید خودش، با in_gallery True ---
        r2 = client.get(
            "/api/v1/generations/mine",
            headers={"Authorization": f"Bearer {token2}"},
        )
        items2 = r2.get_json()
        check("mine user2: only own generation",
              [it["id"] for it in items2] == [g_other.id],
              f"got {items2}")
        check("mine user2: in_gallery True",
              items2 and items2[0].get("in_gallery") is True)

        # --- کاربر بدون تولید: لیست خالی (مسیر gen_ids خالی در helper) ---
        _make_user("09120000003")
        token3 = _otp_login(client, "09120000003")
        r3 = client.get(
            "/api/v1/generations/mine",
            headers={"Authorization": f"Bearer {token3}"},
        )
        check("mine empty user: []", r3.status_code == 200 and r3.get_json() == [])

        # --- بدون توکن: 401 مثل قبل ---
        r4 = client.get("/api/v1/generations/mine")
        check("mine unauthenticated: 401", r4.status_code == 401)

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
