import click
from flask import Flask

from .config import Config
from .extensions import db


def create_app(config_class=Config):
    app = Flask(__name__)
    app.config.from_object(config_class)
    db.init_app(app)

    from .routes import (
        admin_bp,
        auth_bp,
        content_bp,
        coins_bp,
        gallery_bp,
        generations_bp,
        uploads_bp,
    )

    app.register_blueprint(auth_bp, url_prefix="/api/v1")
    app.register_blueprint(content_bp, url_prefix="/api/v1")
    app.register_blueprint(generations_bp, url_prefix="/api/v1")
    app.register_blueprint(coins_bp, url_prefix="/api/v1")
    app.register_blueprint(gallery_bp, url_prefix="/api/v1")
    app.register_blueprint(uploads_bp, url_prefix="/api/v1")
    app.register_blueprint(admin_bp, url_prefix="/api/v1")

    @app.get("/health")
    def health():
        return {"ok": True}

    @app.cli.command("cleanup-expired-free-images")
    def cleanup_expired_free_images_command():
        """حذف عکس‌های رایگان (هدیه) قدیمی‌تر از ۲۴ ساعت — نسخه‌ی ۱۳.

        اجرا از cron سرور، ساعتی یک بار (بدون scheduler داخل اپ):
            cd /opt/new-app/backend && /opt/new-app/venv/bin/flask --app run.py cleanup-expired-free-images
        (مسیر venv پایتون سرور را با مسیر واقعی آن جایگزین کنید.)
        یک خط خلاصه چاپ می‌کند و با کد ۰ برمی‌گردد.
        """
        from .services.cleanup import cleanup_expired_free_images

        result = cleanup_expired_free_images()
        click.echo(
            "cleanup-expired-free-images: scanned={scanned} deleted={deleted} "
            "skipped={skipped} files_missing={files_missing}".format(**result)
        )

    return app
