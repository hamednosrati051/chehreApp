"""Backfill for the Nourai-port migration (runs on the server only).

- Fills model slugs / provider_model_name / provider where empty.
- Copies legacy creds (base_url column + flat config_json.api_key) into
  config_json.__provider__ (Nourai's pattern); legacy values are left in
  place as fallback, never printed.
- Creates the admin user 'admin' with a random password that is hashed
  and immediately discarded (never printed, never stored); the owner
  chooses the real password afterwards.
"""

import secrets

from werkzeug.security import generate_password_hash

from app import create_app
from app.extensions import db
from app.models import AppAdminUser, AppModel

app = create_app()
with app.app_context():
    for m in AppModel.query.all():
        changed = False
        if not m.slug:
            m.slug = "gpt-image-2.5-sunburst" if m.id == 1 else f"model-{m.id}"
            changed = True
        if m.id == 1:
            if not m.provider_model_name:
                m.provider_model_name = "gpt-image-2.5-sunburst"
                changed = True
            if not m.provider:
                m.provider = "avalai"
                changed = True
        cfg = dict(m.config_json or {})
        prov = dict(cfg.get("__provider__") or {})
        if not prov.get("base_url") and m.base_url:
            prov["base_url"] = m.base_url
            changed = True
        if not prov.get("api_key") and cfg.get("api_key"):
            prov["api_key"] = cfg["api_key"]
            changed = True
        if changed:
            cfg["__provider__"] = prov
            m.config_json = cfg
    existing = AppAdminUser.query.filter_by(username="admin").first()
    if existing:
        print("admin user already exists, id=", existing.id)
    else:
        pw = secrets.token_hex(16)  # discarded immediately, never printed
        u = AppAdminUser(
            username="admin",
            password_hash=generate_password_hash(pw),
            is_active=True,
        )
        db.session.add(u)
        print("admin user created")
    db.session.commit()
    print("models:", AppModel.query.count())
