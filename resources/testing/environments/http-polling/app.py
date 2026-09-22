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

Endpoints called by the connector — see the example mappings in README.md's
"Example mappings" section for how each one is used:
    GET /measurements   -> one freshly-generated mock reading (JSON object).
                           Demo: plain single-topic polling.
    GET /status         -> one freshly-generated device status (JSON object).
                           Demo, together with /measurements: two mappings,
                           different topics, same connector instance.
    GET /events?since=  -> a growing list of synthetic events (JSON array),
                           optionally filtered to those after a given `id`.
                           Demo: the incremental-fetch cursor feature —
                           `since` is `cursorParam`, `id` is what
                           `cursorExtractionExpression` reads back out.

Inspection endpoints (not called by the mapper) — the inbound equivalent of
a RequestBin: since polling is inbound (nothing arrives at the *mapper's*
HTTP endpoint to inspect), this service instead records every request *it*
received, so you can verify poll timing, headers, and auth from the outside:
    GET    /requests    -> list of recent poll requests across all three endpoints
                           above (time, path, headers)
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

# Growing, in-memory list of synthetic events for the /events cursor demo — cleared on
# restart. One new event is appended on every /events call, regardless of whether a
# cursor was sent, so both the plain-poll mapping and the cursor-based mapping have
# something new to observe.
_MAX_STORED_EVENTS = 500
_events_store: list = []
_event_seq = 0


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


def _log_request() -> None:
    """Records one entry in the shared /requests inspection log — shared by every
    poll endpoint (/measurements, /status, /events) so `GET /requests` shows the
    combined timeline across all of them, tagged by which path was hit."""
    _request_log.append({
        "path": request.path,
        "receivedAt": _now(),
        "headers": {
            key: "<redacted>" if key.lower() == "authorization" else value
            for key, value in request.headers.items()
        },
        "authorized": _authorized(),
    })


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
    _log_request()

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
# /status  (polled by the REST Polling connector — second topic, same connector
# instance as /measurements; see "Example mappings" in README.md)
# ---------------------------------------------------------------------------

@app.route("/status", methods=["GET"])
def status():
    _log_request()

    if not _authorized():
        logger.warning("GET /status – rejected, bad/missing %s credentials", AUTH_MODE)
        return jsonify({"error": "Unauthorized"}), 401

    reading = {
        "deviceId": DEVICE_ID,
        "timestamp": _now(),
        "status": random.choice(["OK", "OK", "OK", "WARNING", "CRITICAL"]),
    }
    logger.info("GET /status – served reading: %s", reading)
    return jsonify(reading), 200


# ---------------------------------------------------------------------------
# /events  (polled by the REST Polling connector — incremental-fetch cursor demo;
# see "Example mappings" in README.md)
# ---------------------------------------------------------------------------

@app.route("/events", methods=["GET"])
def events():
    global _event_seq
    _log_request()

    if not _authorized():
        logger.warning("GET /events – rejected, bad/missing %s credentials", AUTH_MODE)
        return jsonify({"error": "Unauthorized"}), 401

    # Simulate one new event "arriving" on every poll, whether or not a cursor was
    # sent, so there's always something new to see regardless of which mapping polls.
    _event_seq += 1
    _events_store.append({
        "id": _event_seq,
        "deviceId": DEVICE_ID,
        "timestamp": _now(),
        "text": f"Synthetic poll event #{_event_seq}",
    })
    del _events_store[:-_MAX_STORED_EVENTS]

    since = request.args.get("since")
    if since is not None:
        try:
            since_id = int(since)
            result = [e for e in _events_store if e["id"] > since_id]
        except ValueError:
            logger.warning("GET /events – ignoring non-numeric since=%r", since)
            result = list(_events_store)
    else:
        result = list(_events_store)

    logger.info("GET /events – since=%s, returning %d event(s)", since, len(result))
    return jsonify(result), 200


# ---------------------------------------------------------------------------
# /requests  (inspection endpoint — not called by the mapper)
# ---------------------------------------------------------------------------

@app.route("/requests", methods=["GET"])
def get_requests():
    """Returns the log of requests received at /measurements, /status, and /events
    (each entry tagged with `path`), most recent last."""
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
