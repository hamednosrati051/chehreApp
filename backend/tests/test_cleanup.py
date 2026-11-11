"""تست نسخه‌ی ۱۳ (حذف عکس‌های رایگان قدیمی‌تر از ۲۴ ساعت).

اجرا (بدون pytest؛ فقط stdlib + وابستگی‌های خود بک‌اند):
    python3 backend/tests/test_cleanup.py
اگر flask در همین پایتون نصب نباشد، اسکریپت خودش را با
~/workspace/chehre-v13-24h-cleanup/test-venv/bin/python دوباره
اجرا می‌کند (venv تست محلی؛ روی سرور لازم نیست).

دیتابیس: SQLite در حافظه؛ static_folder یک tmpdir است تا حذف فایل
واقعاً چک شود. هیچ تماسی با سرور، MySQL یا API پولی ندارد.
"""

import datetime
import os
import sys
import tempfile

# --- bootstrap: flask را پیدا کن، وگرنه با venv تست دوباره اجرا شو ---
try:
    import flask  # noqa: F401
except ImportError:
    _here = os.path.dirname(os.path.abspath(__file__))  # .../backend/tests
    _workspace = os.path.dirname(os.path.dirname(os.path.dirname(_here)))
    _venv_python = os.path.join(
        _workspace,
        "chehre-v13-24h-cleanup",
        "test-venv",
        "bin",
        "python",
    )
    if os.path.exists(_venv_python) and os.path.abspath(sys.executable) != _venv_python:
        os.execv(_venv_python, [_venv_python, os.path.abspath(__file__)] + sys.argv[1:])
    print("FAIL: flask is not installed; install backend requirements first")
    sys.exit(2)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import jwt  # noqa: E402

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import AppCoinLedger, AppGallery, AppGeneration, AppUser  # noqa: E402
from app.services.cleanup import cleanup_expired_free_images  # noqa: E402

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(f"{'PASS' if ok else 'FAIL'}: {name}" + (f" — {detail}" if detail else ""))


def utc_now_naive():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


def main():
    tmpdir = tempfile.mkdtemp(prefix="chehre-v13-test-")
    uploads = os.path.join(tmpdir, "uploads")
    os.makedirs(uploads, exist_ok=True)

    class TestConfig(Config):
        SQLALCHEMY_DATABASE_URI = "sqlite://"
        SECRET_KEY = "test-secret-v13"
        TESTING = True

    app = create_app(TestConfig)
    app.static_folder = tmpdir  # فایل‌ها فقط در tmpdir ساخته/حذف می‌شوند

    with app.app_context():
        db.create_all()
        # SQLite + BigInteger PK: autoincrement فقط برای INTEGER PRIMARY KEY
        # کار می‌کند، پس در تست idها را صریح می‌دهیم (مدل‌ها دست نمی‌خورند).
        _next_id = [0]

        def next_id():
            _next_id[0] += 1
            return _next_id[0]

        user = AppUser(id=next_id(), mobile="09120000000")
        db.session.add(user)
        db.session.commit()

        def make_file(name):
            path = os.path.join(uploads, name)
            with open(path, "wb") as f:
                f.write(b"fake-image-bytes")
            return path

        def make_gen(name, kind="image", status="done", age_hours=25,
                     output_name=None, paid=False, gallery=False):
            completed = utc_now_naive() - datetime.timedelta(hours=age_hours)
            output_url = None
            if output_name is not None:
                make_file(output_name)
                output_url = f"http://test/static/uploads/{output_name}"
            gen = AppGeneration(
                id=next_id(),
                user_id=user.id, kind=kind, model_id=1,
                input_url="http://test/static/uploads/input-selfie.jpg",
                output_url=output_url, status=status,
                coin_cost=10, charged=True, completed_at=completed,
            )
            db.session.add(gen)
            db.session.commit()
            if paid:
                db.session.add(AppCoinLedger(
                    id=next_id(),
                    user_id=user.id, delta=-10, reason="spend",
                    ref_type="generation", ref_id=gen.id,
                ))
            if gallery:
                db.session.add(AppGallery(
                    id=next_id(),
                    user_id=user.id, generation_id=gen.id,
                    image_url=output_url or "", status="approved",
                ))
            db.session.commit()
            return gen

        # (a) عکس رایگان قدیمی → باید حذف شود
        g_free_old = make_gen("a", output_name="free-old.png")
        # (b) عکس پولی قدیمی → دست نخورد
        g_paid_old = make_gen("b", output_name="paid-old.png", paid=True)
        # (c) عکس رایگان قدیمی با ردیف گالری → دست نخورد
        g_gallery = make_gen("c", output_name="gallery.png", gallery=True)
        # (d) عکس رایگان تازه (<۲۴ ساعت) → دست نخورد
        g_recent = make_gen("d", age_hours=1, output_name="recent.png")
        # (e) نسل صوتی رایگان قدیمی → دست نخورد
        g_voice = make_gen("e", kind="voice", output_name="voice-old.mp3")
        # (f) عکس رایگان قدیمی ناموفق → دست نخورد
        g_failed = make_gen("f", status="failed", output_name="failed.png")
        # (g) عکس رایگان قدیمی با output_url غیرلوکال → رد شود، دست نخورد
        g_nonlocal = make_gen("g", output_name=None)
        g_nonlocal.output_url = "https://cdn.example.com/x/result.png"
        db.session.commit()

        result = cleanup_expired_free_images()

        # --- assertions: cleanup ---
        check("(a) old free image: file deleted",
              not os.path.exists(os.path.join(uploads, "free-old.png")))
        db.session.refresh(g_free_old)
        check("(a) old free image: output_url NULL, record kept",
              g_free_old.output_url is None
              and AppGeneration.query.get(g_free_old.id) is not None)
        check("(a) old free image: input_url untouched",
              g_free_old.input_url == "http://test/static/uploads/input-selfie.jpg")

        db.session.refresh(g_paid_old)
        check("(b) old PAID image untouched",
              g_paid_old.output_url is not None
              and os.path.exists(os.path.join(uploads, "paid-old.png")))

        db.session.refresh(g_gallery)
        check("(c) old free image WITH gallery row untouched",
              g_gallery.output_url is not None
              and os.path.exists(os.path.join(uploads, "gallery.png")))

        db.session.refresh(g_recent)
        check("(d) recent free image (<24h) untouched",
              g_recent.output_url is not None
              and os.path.exists(os.path.join(uploads, "recent.png")))

        db.session.refresh(g_voice)
        check("(e) old free VOICE generation untouched",
              g_voice.output_url is not None
              and os.path.exists(os.path.join(uploads, "voice-old.mp3")))

        db.session.refresh(g_failed)
        check("(f) failed old free image untouched",
              g_failed.output_url is not None
              and os.path.exists(os.path.join(uploads, "failed.png")))

        db.session.refresh(g_nonlocal)
        check("(g) non-local output_url skipped, output_url kept",
              g_nonlocal.output_url == "https://cdn.example.com/x/result.png")

        check("counts: scanned=4 (free-old, paid, gallery, non-local)",
              result["scanned"] == 4, f"got {result}")
        check("counts: deleted=1, skipped=3, files_missing=0",
              result["deleted"] == 1 and result["skipped"] == 3
              and result["files_missing"] == 0, f"got {result}")

        # --- idempotency: اجرای دوم چیزی برای حذف ندارد ---
        result2 = cleanup_expired_free_images()
        check("idempotent: second run deletes 0",
              result2["deleted"] == 0 and result2["scanned"] == 3,
              f"got {result2}")

        # --- missing file: فایل نباشد هم output_url خالی می‌شود ---
        g_missing = make_gen("h", output_name="will-be-missing.png")
        os.unlink(os.path.join(uploads, "will-be-missing.png"))
        result3 = cleanup_expired_free_images()
        db.session.refresh(g_missing)
        check("missing file: output_url NULL, counted as files_missing",
              g_missing.output_url is None and result3["files_missing"] == 1,
              f"got {result3}")

        # --- API fields: my_generations + get_generation ---
        token = jwt.encode(
            {"sub": str(user.id),
             "exp": utc_now_naive() + datetime.timedelta(days=1)},
            "test-secret-v13", algorithm="HS256",
        )
        client = app.test_client()
        headers = {"Authorization": f"Bearer {token}"}

        resp = client.get("/api/v1/generations/mine", headers=headers)
        check("GET /generations/mine returns 200", resp.status_code == 200,
              f"got {resp.status_code}")
        by_id = {row["id"]: row for row in resp.get_json() or []}

        row_a = by_id.get(g_free_old.id, {})
        check("API (a): expired free image -> is_free=true, expired=true, expires_at=null",
              row_a.get("is_free") is True and row_a.get("expired") is True
              and row_a.get("expires_at") is None and row_a.get("output_url") is None,
              f"got {row_a}")
        row_d = by_id.get(g_recent.id, {})
        expected_exp = (
            g_recent.completed_at + datetime.timedelta(hours=24)
        ).isoformat() + "Z"
        check("API (d): recent free image -> is_free=true, expired=false, expires_at=completed+24h",
              row_d.get("is_free") is True and row_d.get("expired") is False
              and row_d.get("expires_at") == expected_exp,
              f"got {row_d}")
        row_b = by_id.get(g_paid_old.id, {})
        check("API (b): paid image -> is_free=false, expires_at=null",
              row_b.get("is_free") is False and row_b.get("expires_at") is None,
              f"got {row_b}")
        # فیلدهای قدیمی هنوز سر جایشان‌اند
        check("API: old fields unchanged (kind/status/output_url/world_id)",
              all("kind" in r and "status" in r and "output_url" in r
                  and "world_id" in r for r in by_id.values()))

        resp1 = client.get(f"/api/v1/generations/{g_recent.id}", headers=headers)
        one = resp1.get_json() or {}
        check("API get_generation: same v13 fields on single generation",
              resp1.status_code == 200 and one.get("is_free") is True
              and one.get("expires_at") == expected_exp,
              f"got {one}")

    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
