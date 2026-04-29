"""
my-processor microservice
=========================
Test microservice for the Dynamic Mapper "custom routing" feature.

The Dynamic Mapper maps the Smart Function ``action`` field to an HTTP method
before calling this service at ``/service/my-processor/ingest``:

    action   →  HTTP method
    ------      -----------
    create   →  POST    (add a new reading for the device)
    update   →  PUT     (replace the current reading for the device)
    patch    →  PATCH   (partially update the current reading for the device)
    delete   →  DELETE  (remove all readings for the device)

Expected JSON body (all methods except DELETE):
    {
        "deviceId":  "sensor-berlin-01",
        "timestamp": "2026-04-29T12:00:00.000Z",
        "reading":   23.5
    }

DELETE body (minimal):
    { "deviceId": "sensor-berlin-01" }

Inspection endpoints (not called by the mapper):
    GET  /readings               → list all stored readings (keyed by deviceId)
    GET  /readings/<deviceId>    → list readings for one device
    DELETE /readings             → clear the whole store
    GET  /health                 → liveness / readiness probe

/execute  (called by template-SMART-OUTBOUND-05.js)
----------
Forwards a Cumulocity operation to this service.  Only action: create (POST)
is used by the outbound template.

Expected JSON body:
    {
        "deviceId":   "12345",
        "command":    "reboot",
        "operationId": "99001",
        "timestamp":  "2026-04-29T12:00:00.000Z"
    }

Inspection endpoints:
    GET    /commands               → list all received commands
    GET    /commands/<deviceId>    → list commands for one device
    DELETE /commands               → clear the command store
"""

import json
import logging
import os
from collections import defaultdict
from datetime import datetime, timezone

from flask import Flask, jsonify, request

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("my-processor")

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
app = Flask(__name__)

# In-memory reading store: { deviceId: [entry, ...] }
# Cleared on restart — demo only; replace with a real store for production use.
_store: dict[str, list[dict]] = defaultdict(list)

# In-memory command store: { deviceId: [entry, ...] }
_commands: dict[str, list[dict]] = defaultdict(list)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_body(required_fields: list[str]):
    """Parse JSON body and validate required fields. Returns (body, error_response)."""
    body = request.get_json(silent=True)
    if body is None:
        return None, (jsonify({"error": "Request body must be valid JSON"}), 400)
    for field in required_fields:
        if field not in body:
            return None, (jsonify({"error": f"Missing required field: {field}"}), 400)
    return body, None


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    """Cumulocity liveness / readiness probe."""
    return jsonify({"status": "UP"}), 200


# ---------------------------------------------------------------------------
# /ingest  (called by the Dynamic Mapper for all four actions)
# ---------------------------------------------------------------------------

@app.route("/ingest", methods=["POST"])
def ingest_create():
    """
    POST /ingest — action: create
    Appends a new reading for the device.
    """
    body, err = _parse_body(["deviceId", "reading"])
    if err:
        logger.warning("POST /ingest – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    entry = {
        "receivedAt": _now(),
        **body,
    }
    _store[device_id].append(entry)
    logger.info("POST /ingest – created entry for %s: %s", device_id, json.dumps(entry))
    return jsonify({"status": "created", "entry": entry}), 201


@app.route("/ingest", methods=["PUT"])
def ingest_update():
    """
    PUT /ingest — action: update
    Replaces all readings for the device with a single new entry (full update).
    """
    body, err = _parse_body(["deviceId", "reading"])
    if err:
        logger.warning("PUT /ingest – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    entry = {
        "receivedAt": _now(),
        **body,
    }
    _store[device_id] = [entry]
    logger.info("PUT /ingest – replaced readings for %s: %s", device_id, json.dumps(entry))
    return jsonify({"status": "updated", "entry": entry}), 200


@app.route("/ingest", methods=["PATCH"])
def ingest_patch():
    """
    PATCH /ingest — action: patch
    Merges the supplied fields into the most recent reading for the device.
    Creates a new entry if no previous reading exists.
    """
    body, err = _parse_body(["deviceId"])
    if err:
        logger.warning("PATCH /ingest – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    existing = _store[device_id][-1].copy() if _store[device_id] else {}
    existing.update(body)
    existing["updatedAt"] = _now()

    if _store[device_id]:
        _store[device_id][-1] = existing
    else:
        existing.setdefault("receivedAt", existing["updatedAt"])
        _store[device_id].append(existing)

    logger.info("PATCH /ingest – patched reading for %s: %s", device_id, json.dumps(existing))
    return jsonify({"status": "patched", "entry": existing}), 200


@app.route("/ingest", methods=["DELETE"])
def ingest_delete():
    """
    DELETE /ingest — action: delete
    Removes all stored readings for the given device.
    """
    body, err = _parse_body(["deviceId"])
    if err:
        logger.warning("DELETE /ingest – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    removed = len(_store.pop(device_id, []))
    logger.info("DELETE /ingest – removed %d reading(s) for %s", removed, device_id)
    return jsonify({"status": "deleted", "deviceId": device_id, "removed": removed}), 200


# ---------------------------------------------------------------------------
# /readings  (inspection endpoints — not called by the mapper)
# ---------------------------------------------------------------------------

@app.route("/readings", methods=["GET"])
def get_all_readings():
    """Returns all stored readings grouped by deviceId."""
    return jsonify(dict(_store)), 200


@app.route("/readings/<device_id>", methods=["GET"])
def get_device_readings(device_id: str):
    """Returns all stored readings for a specific device."""
    entries = _store.get(device_id)
    if entries is None:
        return jsonify({"error": f"No readings found for deviceId '{device_id}'"}), 404
    return jsonify(entries), 200


@app.route("/readings", methods=["DELETE"])
def clear_all_readings():
    """Clears the entire in-memory store (useful during testing)."""
    count = sum(len(v) for v in _store.values())
    _store.clear()
    logger.info("Store cleared — removed %d reading(s)", count)
    return jsonify({"status": "ok", "removed": count}), 200


# ---------------------------------------------------------------------------
# /execute  (called by template-SMART-OUTBOUND-05.js  action: create → POST)
# ---------------------------------------------------------------------------

@app.route("/execute", methods=["POST"])
def execute_create():
    """
    POST /execute — action: create
    Records an operation/command forwarded by the outbound Smart Function.

    Expected JSON body:
        {
            "deviceId":    <str>,
            "command":     <str>,
            "operationId": <str>,   (optional)
            "timestamp":   <ISO-8601 str>  (optional)
        }
    """
    body, err = _parse_body(["deviceId", "command"])
    if err:
        logger.warning("POST /execute – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    entry = {
        "receivedAt": _now(),
        **body,
    }
    _commands[device_id].append(entry)
    logger.info("POST /execute – queued command for %s: %s", device_id, json.dumps(entry))
    return jsonify({"status": "accepted", "entry": entry}), 201


@app.route("/execute", methods=["PUT"])
def execute_update():
    """
    PUT /execute — action: update
    Replaces the last command for the device (full update).
    """
    body, err = _parse_body(["deviceId", "command"])
    if err:
        logger.warning("PUT /execute – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    entry = {"receivedAt": _now(), **body}
    _commands[device_id] = [entry]
    logger.info("PUT /execute – replaced command for %s: %s", device_id, json.dumps(entry))
    return jsonify({"status": "updated", "entry": entry}), 200


@app.route("/execute", methods=["PATCH"])
def execute_patch():
    """
    PATCH /execute — action: patch
    Merges fields into the most recent command for the device.
    """
    body, err = _parse_body(["deviceId"])
    if err:
        logger.warning("PATCH /execute – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    existing = _commands[device_id][-1].copy() if _commands[device_id] else {}
    existing.update(body)
    existing["updatedAt"] = _now()
    if _commands[device_id]:
        _commands[device_id][-1] = existing
    else:
        existing.setdefault("receivedAt", existing["updatedAt"])
        _commands[device_id].append(existing)
    logger.info("PATCH /execute – patched command for %s: %s", device_id, json.dumps(existing))
    return jsonify({"status": "patched", "entry": existing}), 200


@app.route("/execute", methods=["DELETE"])
def execute_delete():
    """
    DELETE /execute — action: delete
    Removes all stored commands for the given device.
    """
    body, err = _parse_body(["deviceId"])
    if err:
        logger.warning("DELETE /execute – bad request: %s", err[0].get_json())
        return err

    device_id = body["deviceId"]
    removed = len(_commands.pop(device_id, []))
    logger.info("DELETE /execute – removed %d command(s) for %s", removed, device_id)
    return jsonify({"status": "deleted", "deviceId": device_id, "removed": removed}), 200


# ---------------------------------------------------------------------------
# /commands  (inspection endpoints — not called by the mapper)
# ---------------------------------------------------------------------------

@app.route("/commands", methods=["GET"])
def get_all_commands():
    """Returns all stored commands grouped by deviceId."""
    return jsonify(dict(_commands)), 200


@app.route("/commands/<device_id>", methods=["GET"])
def get_device_commands(device_id: str):
    """Returns all stored commands for a specific device."""
    entries = _commands.get(device_id)
    if entries is None:
        return jsonify({"error": f"No commands found for deviceId '{device_id}'"}), 404
    return jsonify(entries), 200


@app.route("/commands", methods=["DELETE"])
def clear_all_commands():
    """Clears the entire command store (useful during testing)."""
    count = sum(len(v) for v in _commands.values())
    _commands.clear()
    logger.info("Command store cleared — removed %d command(s)", count)
    return jsonify({"status": "ok", "removed": count}), 200


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    port = int(os.environ.get("SERVER_PORT", 80))
    logger.info("Starting my-processor on port %d", port)
    app.run(host="0.0.0.0", port=port, debug=False)
