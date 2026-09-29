from threading import Thread, Lock
from concurrent.futures import ThreadPoolExecutor
import argparse
import queue
import paho.mqtt.client as mqtt_client
import logging
import os, tempfile, time, random, json, signal, sys, math
from datetime import datetime, timezone

from mqtt_load_common import (
    add_common_args, get_env, provision_client_certs, cleanup_client_certs, resolve_tenant,
)


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger.info("Load test script started")


def parse_args():
    parser = argparse.ArgumentParser(
        description="MQTT inbound load test against the Cumulocity MQTT Service. "
                    "Publishes randomized geolocation events at a target aggregate TPS, "
                    "spread across as many worker connections as needed to stay under "
                    "the broker's per-client rate cap.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    add_common_args(parser, default_total_tps=1000)
    parser.add_argument(
        "--event-num", type=int, default=int(get_env("EVENT_NUM", 10)),
        help="Number of distinct simulated devices (env: EVENT_NUM)",
    )
    parser.add_argument(
        "--queue-size", type=int, default=int(get_env("QUEUE_SIZE", 5000)),
        help="Max number of pending messages buffered ahead of publishing (env: QUEUE_SIZE)",
    )
    return parser.parse_args()


args = parse_args()

# Broker: explicit override > C8Y_DOMAIN > fallback
broker = args.broker or "broker.emqx.io"
port = args.port

# Username/password auth (only used with --auth password): explicit override > C8Y_TENANT/C8Y_USERNAME
c8y_tenant = get_env("C8Y_TENANT", "")
c8y_username = get_env("C8Y_USERNAME") or get_env("C8Y_USER", "")
username = get_env("MQTT_USERNAME") or (
    f"{c8y_tenant}/{c8y_username}" if c8y_tenant and c8y_username else ""
)

# Password: explicit override > raw JWT from C8Y_HEADER_AUTHORIZATION (strip "Bearer ")
_auth_header = get_env("C8Y_HEADER_AUTHORIZATION", "")
_jwt_token = _auth_header.removeprefix("Bearer ").strip()
password = get_env("MQTT_PASSWORD") or _jwt_token

if args.auth == "password":
    logger.info(f"MQTT broker={broker}  port={port}  username={username}")
    logger.info(f"Auth: {'JWT token (Bearer)' if _jwt_token else 'MQTT_PASSWORD env var'}")
else:
    c8y_tenant = resolve_tenant()
    logger.info(f"MQTT broker={broker}  port={port}  tenant={c8y_tenant}")
    logger.info("Auth: X.509 client certificate (one per worker connection)")

root_topic = "testmapper/"
geodict_topic = "geodict"
qos = 0

task_queue = queue.Queue()

_counter_lock = Lock()
_message_create_count = 0
_message_publish_count = 0
_message_fail_count = 0


def inc_created():
    global _message_create_count
    with _counter_lock:
        _message_create_count += 1


def inc_published():
    global _message_publish_count
    with _counter_lock:
        _message_publish_count += 1


def inc_failed():
    global _message_fail_count
    with _counter_lock:
        _message_fail_count += 1


def snapshot_counters():
    with _counter_lock:
        return _message_create_count, _message_publish_count, _message_fail_count


#### Test parameters
EVENT_NUM = args.event_num
QUEUE_SIZE = args.queue_size

TOTAL_TPS = args.total_tps
MAX_TPS_PER_CLIENT = args.max_tps_per_client

WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_CLIENT)
TPS_PER_CLIENT = TOTAL_TPS / WORKERS

# Connect workers concurrently, bounded, instead of one at a time: a fully serial
# connect loop dilutes measured throughput with several seconds of ramp-up before
# the last worker ever publishes, while connecting all of them at once risks
# tripping broker-side connection rate-limiting.
CONNECT_CONCURRENCY = min(WORKERS, 5)

capid_list = []
device_num = EVENT_NUM

# Cert-auth state: one certificate per worker connection (clientId == cert CN).
_cert_dir = None
_client_certs = []

# Set once all workers are connected and publishing begins (see run()), so
# throughput stats exclude cert provisioning + connection ramp-up time.
_publish_start_time = None


def create_capid(n):
    for i in range(1, n + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt(worker_index: int = 0):
    def on_connect(client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print(f"Failed to connect, return code {rc}")

    if args.auth == "cert":
        cert = _client_certs[worker_index]
        client_id = cert.client_id
    else:
        client_id = f"python-mqtt-{random.randint(0, 10000)}"

    client = mqtt_client.Client(
        client_id=client_id,
        callback_api_version=mqtt_client.CallbackAPIVersion.VERSION2,
    )

    if args.auth == "cert":
        # clientId MUST equal the cert CN, tenant id goes in the username field,
        # no password is used — the client certificate is the credential.
        client.username_pw_set(c8y_tenant)
        client.tls_set(certfile=cert.cert_path, keyfile=cert.key_path)
        logger.info(f"Worker {worker_index}: using client certificate CN={cert.client_id}")
    else:
        if username and password and username.strip() and password.strip():
            client.username_pw_set(username, password)
            logger.info(f"Using authentication with username: {username}")
        else:
            logger.info("Connecting anonymously")
        client.tls_set()

    client.tls_insecure_set(True)
    client.clean_session = True
    client.on_connect = on_connect
    client.connect(broker, port)
    # Without a running network loop, CONNACK/keepalive PINGREQ are never
    # processed and the broker will eventually drop the connection as idle.
    client.loop_start()
    return client


def publish(client, message, topic):
    result = client.publish(topic, message, qos=qos)
    if result[0] == 0:
        inc_published()
    else:
        inc_failed()
        print(f"Failed to send message to topic {topic}")


def create_payload(cap_id: str):
    return {
        "version": "0",
        "id": cap_id,
        "detail-type": "geolocation",
        "source": "myapp.orders",
        "account": "123451235123",
        "time": datetime.now(timezone.utc).isoformat(),
        "region": "us-west-1",
        "detail": {
            "sensorAlternateId": cap_id,
            "capabilityAlternateId": "geolocation",
            "measures": [
                {
                    "latitude": random.uniform(-90, 90),
                    "longitude": random.uniform(-180, 180),
                    "elevation": random.uniform(0, 1000),
                    "accuracy": round(random.uniform(0, 10), 2),
                    "origin": "gps",
                    "gatewayidentifier": "TID-GWID-436521",
                    "_time": datetime.now(timezone.utc).isoformat(),
                }
            ],
        },
    }


def queue_tasks():
    while True:
        if task_queue.qsize() < QUEUE_SIZE:
            tid = random.choice(capid_list)
            task_queue.put(create_payload(tid))
            inc_created()


def consume_tasks(client, tps_per_client=TPS_PER_CLIENT):
    """Token-bucket / next-send-time scheduling: sleep only when ahead of schedule,
    so task_queue.get() latency never silently eats into the rate budget."""
    min_interval = 1.0 / tps_per_client if tps_per_client > 0 else 0
    next_send = time.monotonic()
    while True:
        new_task = task_queue.get()
        now = time.monotonic()
        if now < next_send:
            time.sleep(next_send - now)
        topic = root_topic + geodict_topic
        publish(client, json.dumps(new_task), topic)
        next_send = max(time.monotonic(), next_send + min_interval)
        task_queue.task_done()


def print_stats(start_time):
    created, published, failed = snapshot_counters()
    elapsed = time.time() - (start_time or time.time())
    rate = published / elapsed if elapsed > 0 else 0
    print(
        f"\n{'='*50}\n"
        f"Final Statistics\n"
        f"{'='*50}\n"
        f"Duration:    {elapsed:.1f}s\n"
        f"Created:     {created}\n"
        f"Published:   {published}\n"
        f"Failed:      {failed}\n"
        f"Throughput:  {rate:.1f} msg/s\n"
        f"{'='*50}"
    )


def _connect_worker(i):
    try:
        return connect_mqtt(worker_index=i)
    except Exception as e:
        logger.error(f"Skipping worker {i}: {e}")
        return None


def run():
    """Connect all workers, then start publishing. Sets _publish_start_time to the
    moment publishing actually begins (all workers connected), for accurate
    throughput stats — this deliberately excludes cert provisioning and
    connection ramp-up time."""
    global _publish_start_time

    with ThreadPoolExecutor(max_workers=CONNECT_CONCURRENCY) as pool:
        clients = list(pool.map(_connect_worker, range(WORKERS)))

    connected = 0
    for client in clients:
        if client is None:
            continue
        t = Thread(target=consume_tasks, args=(client,))
        t.daemon = True
        t.start()
        connected += 1
    logger.info(f"Started {connected}/{WORKERS} publisher threads (each a dedicated MQTT client, capped at {TPS_PER_CLIENT} TPS)")

    _publish_start_time = time.time()

    producer = Thread(target=queue_tasks)
    producer.daemon = True
    producer.start()
    logger.info("Producer thread started")

    while True:
        time.sleep(1)
        created, published, failed = snapshot_counters()
        logger.info(f"created={created} published={published} failed={failed} queue={task_queue.qsize()}")


def main():
    global _cert_dir, _client_certs

    create_capid(device_num)

    if args.auth == "cert":
        _cert_dir = tempfile.mkdtemp(prefix="dm-loadtest03-certs-")
        logger.info(f"Provisioning {WORKERS} client certificate(s) for cert auth ...")
        _client_certs = provision_client_certs(WORKERS, _cert_dir, days=args.cert_days, prefix="dmload03")
        logger.info(f"Provisioned {len(_client_certs)} client certificate(s).")

    def _cleanup():
        if args.auth == "cert" and _client_certs:
            logger.info("Cleaning up provisioned client certificates ...")
            cleanup_client_certs(_client_certs, _cert_dir)

    def _shutdown(sig, frame):
        print("\nShutting down gracefully...")
        print_stats(_publish_start_time)
        _cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        run()
    finally:
        print_stats(_publish_start_time)
        _cleanup()


if __name__ == "__main__":
    main()
