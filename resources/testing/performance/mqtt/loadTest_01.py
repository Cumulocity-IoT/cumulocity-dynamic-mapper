import argparse
import json
import logging
import math
import signal
import sys
import tempfile
import time
import threading
import random

import paho.mqtt.client as mqtt

from mqtt_load_common import (
    add_common_args, get_env, provision_client_certs, cleanup_client_certs, resolve_tenant,
)


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Simple MQTT inbound load test: publishes a fixed number of "
                    "temperature readings for one device at a target aggregate TPS, "
                    "spread across as many worker connections as needed to stay "
                    "under the broker's per-client rate cap.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    add_common_args(parser, default_total_tps=10)
    parser.add_argument(
        "--message-count", type=int, default=int(get_env("MESSAGE_COUNT", 1000)),
        help="Total number of messages to publish across all workers (env: MESSAGE_COUNT)",
    )
    parser.add_argument(
        "--topic", default=get_env("MQTT_TOPIC", "loadTestJSONata/berlin_01"),
        help="MQTT topic to publish to (env: MQTT_TOPIC)",
    )
    return parser.parse_args()


args = parse_args()

# Broker: explicit override > C8Y_DOMAIN > public test broker
broker = args.broker or "broker.emqx.io"
port = args.port
topic = args.topic

c8y_tenant = ""
if args.auth == "cert":
    c8y_tenant = resolve_tenant()
    logger.info(f"MQTT broker={broker}  port={port}  tenant={c8y_tenant}")
    logger.info("Auth: X.509 client certificate (one per worker connection)")
else:
    logger.info(f"MQTT broker={broker}  port={port}")

starting_temp = 50
message_count = args.message_count

TOTAL_TPS = args.total_tps
MAX_TPS_PER_CLIENT = args.max_tps_per_client
WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_CLIENT)
TPS_PER_CLIENT = TOTAL_TPS / WORKERS

client_id_prefix = "python-mqtt-sender-"

# Cert-auth state: one certificate per worker connection (clientId == cert CN).
_cert_dir = None
_client_certs = []


def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        logger.info(f"Client {client._client_id.decode()} connected successfully")
    else:
        logger.error(f"Client {client._client_id.decode()} failed to connect, return code {rc}")


def on_publish(client, userdata, mid, properties=None):
    logger.debug(f"Client {client._client_id.decode()} message ID: {mid} published")


def connect_mqtt(worker_index: int):
    if args.auth == "cert":
        cert = _client_certs[worker_index]
        client_id = cert.client_id
    else:
        client_id = client_id_prefix + str(worker_index)

    client = mqtt.Client(client_id=client_id, callback_api_version=mqtt.CallbackAPIVersion.VERSION1)

    if args.auth == "cert":
        # clientId MUST equal the cert CN, tenant id goes in the username field,
        # no password is used — the client certificate is the credential.
        client.username_pw_set(c8y_tenant)
        client.tls_set(certfile=cert.cert_path, keyfile=cert.key_path)
        logger.info(f"Worker {worker_index}: using client certificate CN={cert.client_id}")
    else:
        client.tls_set()
        if args.auth == "password":
            username = get_env("MQTT_USERNAME") or get_env("C8Y_USERNAME")
            password = get_env("MQTT_PASSWORD") or get_env("C8Y_PASSWORD")
            if not username or not password:
                raise ValueError(
                    "Password auth requires MQTT_USERNAME and MQTT_PASSWORD "
                    "(or C8Y_USERNAME and C8Y_PASSWORD)"
                )
            client.username_pw_set(username, password)

    client.on_connect = on_connect
    client.on_publish = on_publish
    client.connect(broker, port, 60)
    client.loop_start()
    return client


def send_messages(worker_index, messages_for_worker, tps_per_client):
    client = connect_mqtt(worker_index)
    min_interval = 1.0 / tps_per_client if tps_per_client > 0 else 0
    next_send = time.monotonic()
    try:
        thread_temp_offset = worker_index * messages_for_worker
        for i in range(messages_for_worker):
            temperature = starting_temp + thread_temp_offset + i
            payload = {
                "temperature": temperature,
                "oil": 40,  # Fixed value as per requirement
                "unit": "C",
                "externalId": "berlin_01",
            }
            message = json.dumps(payload)

            now = time.monotonic()
            if now < next_send:
                time.sleep(next_send - now)
            result = client.publish(topic, message, qos=1)
            if result[0] == 0:
                logger.debug(f"Worker {worker_index} sent: {message}")
            else:
                logger.error(f"Worker {worker_index} failed to send message")
            next_send = max(time.monotonic(), next_send + min_interval)

        client.loop_stop()
        client.disconnect()
        logger.info(f"Worker {worker_index} completed sending {messages_for_worker} messages")
    except Exception as e:
        logger.error(f"Error in worker {worker_index}: {e}")


def main():
    global _cert_dir, _client_certs

    if args.auth == "cert":
        _cert_dir = tempfile.mkdtemp(prefix="dm-loadtest01-certs-")
        logger.info(f"Provisioning {WORKERS} client certificate(s) for cert auth ...")
        _client_certs = provision_client_certs(WORKERS, _cert_dir, days=args.cert_days, prefix="dmload01")
        logger.info(f"Provisioned {len(_client_certs)} client certificate(s).")

    def _cleanup():
        if args.auth == "cert" and _client_certs:
            logger.info("Cleaning up provisioned client certificates ...")
            cleanup_client_certs(_client_certs, _cert_dir)

    def _shutdown(sig, frame):
        print("\nShutting down gracefully...")
        _cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    threads = []
    try:
        for i in range(WORKERS):
            messages_for_worker = message_count // WORKERS + (1 if i < message_count % WORKERS else 0)
            thread = threading.Thread(target=send_messages, args=(i, messages_for_worker, TPS_PER_CLIENT))
            threads.append(thread)
            thread.start()

        for thread in threads:
            thread.join()

        logger.info("All messages sent successfully")
    finally:
        _cleanup()


if __name__ == "__main__":
    main()
