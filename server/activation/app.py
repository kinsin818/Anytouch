#!/usr/bin/env python3
"""Anytouch activation endpoint (S5-g). Stdlib only — no third-party dependency on this box.

Server-side authority for activation: a code unlocks only if it is in the whitelist AND the
device hash fits inside the two-seat cap. The client keeps its format check purely as a
pre-filter; a well-formed code that is not in `codes` gets the same answer as garbage.

Never logged, never returned, never stored outside SQLite: full activation codes, full device
hashes, the admin token. Log lines carry a code's last 4 chars and a device hash's first 8.
"""
import json
import os
import re
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("ACTIVATION_DB", "/var/lib/anytouch-activate/activation.db")
CERT = os.environ.get("TLS_CERT", "/etc/tls/fullchain.pem")
KEY = os.environ.get("TLS_KEY", "/etc/tls/privkey.pem")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")
PORT = int(os.environ.get("PORT", "8443"))
SEATS_TOTAL = 2
RATE_LIMIT_PER_MIN = 30

CODE_RE = re.compile(r"^ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
HASH_RE = re.compile(r"^[0-9a-f]{8,64}$")

_db_lock = threading.Lock()
_hits: dict[str, list[float]] = {}
_hits_lock = threading.Lock()


class _Conn:
    """Context manager that actually closes; sqlite3's own one only commits."""

    def __init__(self):
        self.conn = None

    def __enter__(self):
        conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        self.conn = conn
        return conn

    def __exit__(self, *exc):
        self.conn.close()
        return False


def code_tail(code: str) -> str:
    return code[-4:] if len(code) >= 4 else "****"


def hash_head(device: str) -> str:
    return device[:8]


def throttled(ip: str) -> bool:
    now = time.time()
    with _hits_lock:
        window = [t for t in _hits.setdefault(ip, []) if now - t < 60]
        window.append(now)
        _hits[ip] = window
        return len(window) > RATE_LIMIT_PER_MIN


def seat_count(conn: sqlite3.Connection, code: str) -> int:
    return conn.execute("SELECT COUNT(*) FROM bindings WHERE code=?", (code,)).fetchone()[0]


def ensure_schema():
    with _db_lock, _Conn() as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS codes("
            "code TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('buyer','staging')), "
            "created_at TEXT NOT NULL)"
        )
        conn.execute(
            "CREATE TABLE IF NOT EXISTS bindings("
            "code TEXT NOT NULL, device_hash TEXT NOT NULL, bound_at TEXT NOT NULL, "
            "PRIMARY KEY(code, device_hash))"
        )


class Handler(BaseHTTPRequestHandler):
    server_version = "AnytouchActivate/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # override: stdlib spelling
        print("%s %s" % (self.client_address[0], fmt % args), flush=True)

    def _json(self, status: int, payload: dict):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if length <= 0 or length > 4096:
            return None
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        return data if isinstance(data, dict) else None

    def _admin_ok(self, token) -> bool:
        return bool(ADMIN_TOKEN) and isinstance(token, str) and token == ADMIN_TOKEN

    def _credentials(self, req):
        """Returns (code, device) normalized, or None when either is structurally unusable."""
        code = (req.get("code") or "").strip().upper()
        device = (req.get("device_hash") or "").strip().lower()
        if not CODE_RE.match(code) or not HASH_RE.match(device):
            return None
        return code, device

    def do_GET(self):  # override: stdlib spelling
        if self.path == "/api/health":
            self._json(200, {"ok": True, "service": "anytouch-activate"})
        else:
            self._json(404, {"ok": False, "reason": "not_found"})

    def do_POST(self):  # override: stdlib spelling
        if throttled(self.client_address[0]):
            self._json(429, {"ok": False, "reason": "rate_limited"})
            return
        if self.path == "/api/activate":
            self._activate()
        elif self.path == "/api/deactivate":
            self._deactivate()
        elif self.path == "/api/admin/lease-staging":
            self._lease_staging()
        elif self.path == "/api/admin/reset-staging":
            self._reset_staging()
        else:
            self._json(404, {"ok": False, "reason": "not_found"})

    def _activate(self):
        req = self._read_json()
        creds = self._credentials(req) if req is not None else None
        if creds is None:
            self._json(200, {"ok": False, "reason": "invalid"})
            return
        code, device = creds
        with _db_lock, _Conn() as conn:
            # BEGIN IMMEDIATE: count and insert must not interleave, or two concurrent requests
            # against a code holding one seat would each see "1 < 2" and bind a third device.
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT kind FROM codes WHERE code=?", (code,)).fetchone()
            if row is None:
                denied = "invalid"
            elif conn.execute(
                "SELECT 1 FROM bindings WHERE code=? AND device_hash=?", (code, device)
            ).fetchone() is not None:
                denied = None  # already bound: idempotent re-activation, no new seat taken
            elif seat_count(conn, code) >= SEATS_TOTAL:
                denied = "seats_full"
            else:
                conn.execute(
                    "INSERT INTO bindings(code, device_hash, bound_at) VALUES(?,?,?)",
                    (code, device, time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
                )
                denied = None
            if denied is None:
                seats_used = seat_count(conn, code)
                conn.execute("COMMIT")
            else:
                conn.execute("ROLLBACK")
                seats_used = 0
        if denied is not None:
            self._json(200, {"ok": False, "reason": denied, "seats_total": SEATS_TOTAL})
            print(
                "activate deny code=%s device=%s reason=%s"
                % (code_tail(code), hash_head(device), denied),
                flush=True,
            )
            return
        self._json(200, {"ok": True, "seats_used": seats_used, "seats_total": SEATS_TOTAL})
        print(
            "activate ok code=%s device=%s seats=%d kind=%s"
            % (code_tail(code), hash_head(device), seats_used, row[0]),
            flush=True,
        )

    def _deactivate(self):
        req = self._read_json()
        if req is None or not self._admin_ok(req.get("admin_token")):
            self._json(403, {"ok": False, "reason": "forbidden"})
            return
        creds = self._credentials(req)
        if creds is None:
            self._json(400, {"ok": False, "reason": "invalid"})
            return
        code, device = creds
        with _db_lock, _Conn() as conn:
            removed = conn.execute(
                "DELETE FROM bindings WHERE code=? AND device_hash=?", (code, device)
            ).rowcount
            seats_left = seat_count(conn, code)
        self._json(200, {"ok": True, "removed": removed, "seats_used": seats_left})
        print(
            "deactivate code=%s device=%s removed=%d seats_left=%d"
            % (code_tail(code), hash_head(device), removed, seats_left),
            flush=True,
        )

    def _lease_staging(self):
        req = self._read_json()
        if req is None or not self._admin_ok(req.get("admin_token")):
            self._json(403, {"ok": False, "reason": "forbidden"})
            return
        with _db_lock, _Conn() as conn:
            row = conn.execute(
                "SELECT c.code FROM codes c LEFT JOIN bindings b ON b.code=c.code "
                "WHERE c.kind='staging' GROUP BY c.code "
                "HAVING COUNT(b.device_hash) < ? "
                "ORDER BY COUNT(b.device_hash) ASC, c.code ASC LIMIT 1",
                (SEATS_TOTAL,),
            ).fetchone()
        if row is None:
            self._json(200, {"ok": False, "reason": "no_staging_seat"})
            print("lease-staging deny reason=no_staging_seat", flush=True)
            return
        self._json(200, {"ok": True, "code": row[0], "kind": "staging"})
        print("lease-staging ok code=%s" % code_tail(row[0]), flush=True)


    def _reset_staging(self):
        """Clear every binding the test segment holds so a smoke round can start from a clean slate.

        Scoped by kind in the subquery: a mis-fired admin call can never spend a real buyer's
        quota. Response and log carry counts only — no code, no hash.
        """
        req = self._read_json()
        if req is None or not self._admin_ok(req.get("admin_token")):
            self._json(403, {"ok": False, "reason": "forbidden"})
            return
        with _db_lock, _Conn() as conn:
            removed = conn.execute(
                "DELETE FROM bindings WHERE code IN (SELECT code FROM codes WHERE kind='staging')"
            ).rowcount
            staging_left = conn.execute(
                "SELECT COUNT(*) FROM bindings b JOIN codes c ON c.code=b.code WHERE c.kind='staging'"
            ).fetchone()[0]
            buyer_left = conn.execute(
                "SELECT COUNT(*) FROM bindings b JOIN codes c ON c.code=b.code WHERE c.kind='buyer'"
            ).fetchone()[0]
        self._json(
            200,
            {"ok": True, "removed": removed, "staging_left": staging_left, "buyer_left": buyer_left},
        )
        print(
            "reset-staging removed=%d staging_left=%d buyer_left=%d"
            % (removed, staging_left, buyer_left),
            flush=True,
        )


def main():
    if not ADMIN_TOKEN:
        raise SystemExit("ADMIN_TOKEN unset — refusing to start (admin endpoints would be unguarded)")
    for path in (CERT, KEY):
        if not os.path.exists(path):
            raise SystemExit("missing TLS material at %s — refusing to start on plaintext" % path)
    ensure_schema()
    import ssl

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(CERT, KEY)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print("anytouch-activate listening on %d (tls>=1.2)" % PORT, flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
