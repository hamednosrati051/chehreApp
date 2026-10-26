"""Seed دیتابیس جدای اپ: مدل تصویر خود اپ + تنظیمات پیش‌فرض.

فقط ردیف‌های خود اپ را می‌سازد؛ هیچ تماسی با نورا ندارد.
کلید AvalAI اینجا نیست — حامد آن را در فرم مدل ادمین خود اپ وارد
می‌کند (PATCH /api/v1/admin/models/<id> با هدر X-Admin-Token).

اجرا (با env خود اپ):
    cd backend && APP_DATABASE_URL=... python seed.py
"""

from app import create_app
from app.extensions import db
from app.models import AppCoinPack, AppModel, AppSetting

SUNBURST = {
    "kind": "image",
    "name": "gpt-image-2.5-sunburst",
    "provider": "avalai",
    "base_url": "https://api.avalai.ir/v1",
    "capability": "edit_image",
    "config_json": {
        # placeholder؛ مقدار واقعی فقط از فرم ادمین خود اپ می‌آید.
        "api_key": "",
        "quality": "medium",
    },
}


def main():
    app = create_app()
    with app.app_context():
        db.create_all()
        model = AppModel.query.filter_by(name=SUNBURST["name"]).first()
        if model is None:
            model = AppModel(is_active=True, **SUNBURST)
            db.session.add(model)
            print(f"created model row: {model.name} (id pending)")
        else:
            print(f"model row already exists: {model.name} (id={model.id})")
        setting = AppSetting.query.get("image_coin_price")
        if setting is None:
            db.session.add(AppSetting(key="image_coin_price", value="5"))
            print("created setting: image_coin_price=5")
        else:
            print(f"setting already exists: image_coin_price={setting.value}")
        # پک تست تحویل (دستور حامد 2026-10-26): فقط برای تست پرداخت با
        # همان درگاه اپ نورا — بازار (Bazaar IAB)، نه زیبال. ۵ سکه = دقیقاً
        # یک تصویر با تعرفه‌ی ۵ سکه؛ قیمت کوچک برای تست. bazaar_sku فقط
        # جای‌نگهدارنده است؛ حامد SKU واقعی را در پنل بازار خودش می‌سازد و
        # همین مقدار را اینجا (ادمین خود اپ) جایگزین می‌کند. قیمت پنل بازار
        # باید با price_toman اینجا یکی باشد (تومان × ۱۰ = ریال در پنل).
        pack = AppCoinPack.query.filter_by(bazaar_sku="test_pack").first()
        if pack is None:
            pack = AppCoinPack(
                name="تست",
                coins=5,
                price_toman=1000,
                bazaar_sku="test_pack",
                badge=None,
                sort_order=0,
                is_active=True,
            )
            db.session.add(pack)
            print("created test pack: تست / 5 coins / 1000 Toman / sku=test_pack")
        else:
            print(f"test pack already exists: {pack.name} (sku={pack.bazaar_sku})")
        db.session.commit()
        print("seed done.")


if __name__ == "__main__":
    main()
