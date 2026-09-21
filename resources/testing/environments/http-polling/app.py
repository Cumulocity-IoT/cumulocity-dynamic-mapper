"""
http-polling-mock microservice
===============================
Test microservice for the Dynamic Mapper **REST Polling connector**
(`ConnectorType.REST_POLLING`, inbound only).

The connector issues a plain GET against a configured `url` on a fixed
interval (`pollIntervalSeconds`, minimum 30s) and feeds each successful
response body into the mapping pipeline as-is. This microservice is the
poll *target*: it serves a synthetic, changing reading on every call so a
mapping's output is visibly different across poll cycles, and it optionally
requires Basic or Bearer auth so the connector's `authentication` config can
be exercised end-to-end.

Endpoints called by the connector:
    GET /measurements   -> one freshly-generated mock reading (JSON object)

Inspection endpoints (not called by the mapper) — the inbound equivalent of
a RequestBin: since polling is inbound (nothing arrives at the *mapper's*
HTTP endpoint to inspect), this service instead records every request *it*
received, so you can verify poll timing, headers, and auth from the outside:
    GET    /requests    -> list of recent /measurements requests (time, headers)
    DELETE /requests    -> clear the request log
    GET    /health      -> liveness / readiness probe
"""

import base64
import logging
import os
import random
from collections import deque
from datetime import datetime, timezone

from flask import Flask, jsonify, request

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("http-polling-mock")

# ---------------------------------------------------------------------------
# Configuration (env vars — all optional, auth is off by default)
# ---------------------------------------------------------------------------
AUTH_MODE = os.environ.get("AUTH_MODE", "none").lower()  # none | basic | bearer
AUTH_USER = os.environ.get("AUTH_USER", "poller")
AUTH_PASSWORD = os.environ.get("AUTH_PASSWORD", "secret")
AUTH_TOKEN = os.environ.get("AUTH_TOKEN", "test-token")
DEVICE_ID = os.environ.get("DEVICE_ID", "poll-sensor-01")

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
app = Flask(__name__)

# Bounded in-memory log of requests received at /measurements — demo only,
# cleared on restart.
_MAX_LOGGED_REQUESTS = 200
_request_log: deque = deque(maxlen=_MAX_LOGGED_REQUESTS)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _authorized() -> bool:
    if AUTH_MODE == "none":
        return True
    header = request.headers.get("Authorization", "")
    if AUTH_MODE == "basic":
        expected = "Basic " + base64.b64encode(
            f"{AUTH_USER}:{AUTH_PASSWORD}".encode()
        ).decode()
        return header == expected
    if AUTH_MODE == "bearer":
        return header == f"Bearer {AUTH_TOKEN}"
    return False


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    """Cumulocity liveness / readiness probe. Never requires auth."""
    return jsonify({"status": "UP"}), 200


# ---------------------------------------------------------------------------
# /measurements  (polled by the REST Polling connector)
# ---------------------------------------------------------------------------

@app.route("/measurements", methods=["GET"])
def measurements():
    _request_log.append({
        "receivedAt": _now(),
        "headers": dict(request.headers),
        "authorized": _authorized(),
    })

    if not _authorized():
        logger.warning("GET /measurements – rejected, bad/missing %s credentials", AUTH_MODE)
        return jsonify({"error": "Unauthorized"}), 401

    reading = {
        "deviceId": DEVICE_ID,
        "timestamp": _now(),
        "temperature": round(random.uniform(18.0, 28.0), 2),
    }
    logger.info("GET /measurements – served reading: %s", reading)
    return jsonify(reading), 200


# ---------------------------------------------------------------------------
# /requests  (inspection endpoint — not called by the mapper)
# ---------------------------------------------------------------------------

@app.route("/requests", methods=["GET"])
def get_requests():
    """Returns the log of requests received at /measurements, most recent last."""
    return jsonify(list(_request_log)), 200


@app.route("/requests", methods=["DELETE"])
def clear_requests():
    """Clears the request log (useful before starting a fresh test run)."""
    count = len(_request_log)
    _request_log.clear()
    logger.info("Request log cleared — removed %d entr(ies)", count)
    return jsonify({"status": "ok", "removed": count}), 200


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    port = int(os.environ.get("SERVER_PORT", 80))
    logger.info("Starting http-polling-mock on port %d (AUTH_MODE=%s)", port, AUTH_MODE)
    app.run(host="0.0.0.0", port=port, debug=False)
