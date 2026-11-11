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

    return app
