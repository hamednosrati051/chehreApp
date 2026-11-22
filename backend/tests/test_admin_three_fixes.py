"""سه اصلاح ادمین (بدون نسخه‌ی اپ):

1. مجموع فیلترشده‌ی پرداخت‌ها در GET /admin/purchases
2. user_id در آیتم‌های GET /admin/inputs (لینک کاربر زیر عکس ورودی)
3. DELETE /admin/tickets/<id> (تیکت + پیام‌هایش)

اجرا (روی سرور):
    /opt/new-app/venv/bin/python tests/test_admin_three_fixes.py

فقط خواندنی + حذفِ ردیف‌های تست در دیتابیسِ موقتِ SQLite؛ هیچ فایلی
ساخته یا پاک نمی‌شود و پوشه‌ی واقعی static/uploads دست نمی‌خورد.
"""

import datetime
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinPack,
    AppGeneration,
    AppModel,
    AppPurchase,
    AppTicket,
    AppTicketMessage,
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


_NEXT_ID = [0]


def next_id():
    _NEXT_ID[0] += 1
    return _NEXT_ID[0]


class TestConfig(Config):
    SQLALCHEMY_DATABASE_URI = "sqlite://"
    TESTING = True
    ADMIN_TOKEN = "tc1"


def main():
    app = create_app(TestConfig)
    with app.app_context():
        from sqlalchemy import BigInteger, Integer

        for _table in db.metadata.tables.values():
            for _col in _table.columns:
                if _col.primary_key and isinstance(_col.type, BigInteger):
                    _col.type = Integer()
        db.create_all()
        client = app.test_client()
        admin_h = {"X-Admin-Token": app.config.get("ADMIN_TOKEN") or ""}

        u1 = AppUser(
            id=next_id(), mobile="09120000001",
            free_image_credits=0, free_voice_credits=0,
        )
        u2 = AppUser(
            id=next_id(), mobile="09120000002",
            free_image_credits=0, free_voice_credits=0,
        )
        pack = AppCoinPack(
            id=next_id(), name="test pack", coins=20,
            price_toman=59900, bazaar_sku="test-pack",
        )
        model = AppModel(
            id=next_id(), kind="image", name="test image model",
            slug="test-image", provider="test",
            capability="edit_image", is_active=True,
        )
        world = AppWorld(
            id=next_id(), name="دنیای تست", slug="test-world",
            hidden_prompt="test prompt", model_id=model.id,
            coin_price=10, is_active=True,
        )
        db.session.add_all([u1, u2, pack, model, world])
        db.session.commit()

        d20 = datetime.datetime(2026, 11, 20, 10, 0)
        d21 = datetime.datetime(2026, 11, 21, 10, 0)
        db.session.add_all([
            AppPurchase(id=next_id(), user_id=u1.id, pack_id=pack.id,
                        amount_irr=59900, status="consumed", created_at=d20),
            AppPurchase(id=next_id(), user_id=u1.id, pack_id=pack.id,
                        amount_irr=149900, status="consumed", created_at=d21),
            AppPurchase(id=next_id(), user_id=u1.id, pack_id=pack.id,
                        amount_irr=59900, status="pending",
                        created_at=datetime.datetime(2026, 11, 21, 11, 0)),
            AppPurchase(id=next_id(), user_id=u2.id, pack_id=pack.id,
                        amount_irr=59900, status="consumed",
                        created_at=datetime.datetime(2026, 11, 20, 12, 0)),
        ])
        in1 = "https://chehre.inourai.ir/static/uploads/in1.jpg"
        in2 = "https://chehre.inourai.ir/static/uploads/in2.jpg"
        db.session.add_all([
            AppGeneration(id=next_id(), user_id=u1.id, kind="image",
                          world_id=world.id, model_id=model.id,
                          input_url=in1, status="done"),
            AppGeneration(id=next_id(), user_id=u2.id, kind="image",
                          world_id=world.id, model_id=model.id,
                          input_url=in1, status="done"),
            AppGeneration(id=next_id(), user_id=u1.id, kind="image",
                          world_id=world.id, model_id=model.id,
                          input_url=in2, status="done"),
        ])
        ticket = AppTicket(id=next_id(), user_id=u1.id, subject="test ticket")
        db.session.add(ticket)
        db.session.commit()
        db.session.add_all([
            AppTicketMessage(id=next_id(), ticket_id=ticket.id,
                             sender="user", body="hello"),
            AppTicketMessage(id=next_id(), ticket_id=ticket.id,
                             sender="admin", body="hi"),
        ])
        db.session.commit()

        # --- 1. totals پرداخت‌ها ---
        r = client.get("/api/v1/admin/purchases", headers=admin_h)
        j = r.get_json()
        check("purchases: no filter totals",
              r.status_code == 200 and j["totals"]["count"] == 4
              and j["totals"]["amount_irr"] == 329600,
              f"got {r.status_code} {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases?status=consumed", headers=admin_h)
        j = r.get_json()
        check("purchases: status=consumed totals",
              j["totals"]["count"] == 3 and j["totals"]["amount_irr"] == 269700,
              f"got {j.get('totals')}")
        check("purchases: status filter also filters items",
              len(j["items"]) == 3 and j["total"] == 3)
        r = client.get("/api/v1/admin/purchases?status=pending", headers=admin_h)
        j = r.get_json()
        check("purchases: status=pending totals",
              j["totals"]["count"] == 1 and j["totals"]["amount_irr"] == 59900,
              f"got {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases?date_from=2026-11-21", headers=admin_h)
        j = r.get_json()
        check("purchases: date_from totals (excludes Nov 20)",
              j["totals"]["count"] == 2 and j["totals"]["amount_irr"] == 209800,
              f"got {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases?date_to=2026-11-20", headers=admin_h)
        j = r.get_json()
        check("purchases: date_to totals (only Nov 20)",
              j["totals"]["count"] == 2 and j["totals"]["amount_irr"] == 119800,
              f"got {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases?mobile=09120000002", headers=admin_h)
        j = r.get_json()
        check("purchases: mobile filter totals",
              j["totals"]["count"] == 1 and j["totals"]["amount_irr"] == 59900,
              f"got {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases?status=failed", headers=admin_h)
        j = r.get_json()
        check("purchases: empty filter totals are zero",
              j["totals"]["count"] == 0 and j["totals"]["amount_irr"] == 0,
              f"got {j.get('totals')}")
        r = client.get("/api/v1/admin/purchases")
        check("purchases: no admin token -> 403", r.status_code == 403,
              f"got {r.status_code}")

        # --- 2. user_id در ورودی‌ها ---
        r = client.get("/api/v1/admin/inputs", headers=admin_h)
        j = r.get_json()
        by_url = {}
        for it in j["items"]:
            by_url.setdefault(it["input_url"], set()).add(it["user_id"])
        check("inputs: same input used by two users -> two user_ids",
              by_url.get(in1) == {u1.id, u2.id}, f"got {by_url.get(in1)}")
        check("inputs: in2 belongs to u1",
              by_url.get(in2) == {u1.id}, f"got {by_url.get(in2)}")
        check("inputs: every item carries a user_id",
              all(it.get("user_id") for it in j["items"]))
        check("inputs: mobile resolved for u1 rows",
              any(it["user_id"] == u1.id and it["mobile"] == "09120000001"
                  for it in j["items"]))

        # --- 3. حذف تیکت ---
        r = client.delete(f"/api/v1/admin/tickets/{ticket.id}")
        check("ticket delete: no admin token -> 403", r.status_code == 403,
              f"got {r.status_code}")
        check("ticket delete: 403 left the ticket in place",
              AppTicket.query.get(ticket.id) is not None)
        r = client.delete("/api/v1/admin/tickets/999999", headers=admin_h)
        check("ticket delete: missing id -> 404", r.status_code == 404,
              f"got {r.status_code}")
        r = client.delete(f"/api/v1/admin/tickets/{ticket.id}", headers=admin_h)
        j = r.get_json()
        check("ticket delete: 200 + deleted id",
              r.status_code == 200 and j.get("deleted") == ticket.id,
              f"got {r.status_code} {j}")
        check("ticket delete: ticket row gone",
              AppTicket.query.get(ticket.id) is None)
        check("ticket delete: messages gone",
              AppTicketMessage.query.filter_by(ticket_id=ticket.id).count() == 0)
        r = client.get(f"/api/v1/admin/tickets/{ticket.id}", headers=admin_h)
        check("ticket delete: GET detail afterwards -> 404",
              r.status_code == 404, f"got {r.status_code}")
        r = client.get("/api/v1/admin/tickets", headers=admin_h)
        check("ticket delete: list no longer contains it",
              all(t["id"] != ticket.id for t in r.get_json()))

    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
