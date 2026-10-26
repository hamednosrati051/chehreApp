"""Set the 'admin' user's password from stdin (transient, never printed).

Usage: the chosen password is piped via stdin; only its hash is stored.
"""

import sys

from werkzeug.security import generate_password_hash

from app import create_app
from app.extensions import db
from app.models import AppAdminUser

pw = sys.stdin.read().strip()
assert len(pw) >= 6, "password too short"
app = create_app()
with app.app_context():
    u = AppAdminUser.query.filter_by(username="admin").first()
    assert u is not None, "admin user missing"
    u.password_hash = generate_password_hash(pw)
    u.is_active = True
    db.session.commit()
    print("password set for admin, id=", u.id)
