from threading import Thread, Lock, Event
from concurrent.futures import ThreadPoolExecutor
import argparse
import queue
import uuid
import paho.mqtt.client as mqtt_client
import logging
import os, tempfile, time, random, json, signal, sys, math
from datetime import timezone

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
                    "Publishes randomized telemetry/error messages at a target aggregate "
                    "TPS, spread across as many worker connections as needed to stay "
                    "under the broker's per-client rate cap.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    add_common_args(parser, default_total_tps=10)
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
    if not username:
        raise SystemExit("ERROR: MQTT username could not be determined. Set C8Y_TENANT + C8Y_USERNAME or MQTT_USERNAME.")
    if not password:
        raise SystemExit("ERROR: MQTT password could not be determined. Set C8Y_HEADER_AUTHORIZATION or MQTT_PASSWORD.")
    logger.info(f"MQTT broker={broker}  port={port}  username={username}")
    logger.info(f"Auth: {'JWT token (Bearer)' if _jwt_token else 'MQTT_PASSWORD env var'}")
else:
    c8y_tenant = resolve_tenant()
    logger.info(f"MQTT broker={broker}  port={port}  tenant={c8y_tenant}")
    logger.info("Auth: X.509 client certificate (one per worker connection)")

root_topics = ["smartfunction/performance", "smartfunction/performance2"]
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

# Target aggregate throughput. WORKERS and TPS_PER_CLIENT are derived automatically.
# The broker enforces a hard limit of 100 msg/s per MQTT client; MAX_TPS_PER_CLIENT
# stays slightly below that to give headroom.
TOTAL_TPS = args.total_tps
MAX_TPS_PER_CLIENT = args.max_tps_per_client

WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_CLIENT)
TPS_PER_CLIENT = TOTAL_TPS / WORKERS

# How long (seconds) to wait for each client to connect before giving up
CONNECT_TIMEOUT = 15
# Connect workers concurrently, bounded, instead of one at a time: a fully serial
# connect loop dilutes measured throughput with several seconds of ramp-up before
# the last worker ever publishes, while connecting all of them at once risks
# tripping broker-side connection rate-limiting.
CONNECT_CONCURRENCY = min(WORKERS, 5)

message_type = ["telemetry", "error"]
capid_list = []
device_num = EVENT_NUM

# Cert-auth state: one certificate per worker connection (clientId == cert CN).
# Populated in main() before workers connect, and torn down on exit.
_cert_dir = None
_client_certs = []

# Set once all workers are connected and publishing begins (see run()), so
# throughput stats exclude cert provisioning + connection ramp-up time.
_publish_start_time = None


def create_capid(n):
    for i in range(1, n + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt(worker_index: int = 0) -> mqtt_client.Client:
    """
    Create an MQTT client, start its network loop, and block until the broker
    confirms a successful connection (rc == 0) or the timeout expires.
    Raises RuntimeError if the connection is refused or times out.
    """
    connected_event = Event()
    connect_rc = [None]  # mutable container so the callback can write into it

    def on_connect(client, userdata, flags, rc, properties=None):
        connect_rc[0] = rc
        if rc == 0:
            logger.info(f"Worker {worker_index}: connected to MQTT broker")
            connected_event.set()
        else:
            logger.error(f"Worker {worker_index}: broker refused connection, rc={rc}")
            connected_event.set()  # unblock the wait so we can raise immediately

    def on_disconnect(client, userdata, disconnect_flags, rc, properties=None):
        if rc != 0:
            logger.warning(f"Worker {worker_index}: unexpected disconnect, rc={rc}")

    if args.auth == "cert":
        cert = _client_certs[worker_index]
        client_id = cert.client_id
    else:
        client_id = f"python-mqtt-{worker_index}-{random.randint(0, 10000)}"

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
    client.on_disconnect = on_disconnect

    client.connect(broker, port)
    # Start the background network loop *before* waiting — on_connect fires from this thread.
    client.loop_start()

    if not connected_event.wait(timeout=CONNECT_TIMEOUT):
        client.loop_stop()
        raise RuntimeError(
            f"Worker {worker_index}: timed out waiting for MQTT connection after {CONNECT_TIMEOUT}s"
        )

    if connect_rc[0] != 0:
        client.loop_stop()
        raise RuntimeError(
            f"Worker {worker_index}: broker refused connection with rc={connect_rc[0]}"
        )

    return client


def publish(client, message, topic):
    result = client.publish(topic, message, qos=qos)
    if result[0] == 0:
        inc_published()
    else:
        inc_failed()
        print(f"Failed to send message to topic {topic}, status={result[0]}")


def create_payload(cap_id: str):
    selected_type = random.choice(message_type)
    if selected_type == "telemetry":
        return {
            "messageId": str(uuid.uuid4()),
            "clientId": cap_id.split("-").pop(),
            "payloadType": "telemetry",
            "sensorData": {
                "temp_val": random.uniform(-20, 35),
            },
        }
    else:
        return {
            "messageId": str(uuid.uuid4()),
            "clientId": cap_id.split("-").pop(),
            "payloadType": "error",
            "logMessage": "Sensor malfunction detected",
        }


def queue_tasks():
    while True:
        if task_queue.qsize() < QUEUE_SIZE:
            tid = random.choice(capid_list)
            task_queue.put(create_payload(tid))
            inc_created()


def consume_tasks(client, tps_per_client=TPS_PER_CLIENT):
    """Consume tasks from the shared queue and publish via this worker's dedicated MQTT client.

    Uses a token-bucket / next-send-time approach: we track when the next publish *should*
    happen and sleep only if we are ahead of schedule.  This absorbs the latency of
    task_queue.get() without letting it silently eat into the rate budget the way a
    simple 'sleep(interval - elapsed)' check does.
    """
    min_interval = 1.0 / tps_per_client if tps_per_client > 0 else 0
    next_send = time.monotonic()
    while True:
        new_task = task_queue.get()
        now = time.monotonic()
        if now < next_send:
            time.sleep(next_send - now)
        publish(client, json.dumps(new_task), random.choice(root_topics))
        # Advance the schedule by one slot; if we are already behind, catch up immediately
        # (don't accumulate a debt of sleeps).
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
    except RuntimeError as e:
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
        _cert_dir = tempfile.mkdtemp(prefix="dm-loadtest04-certs-")
        logger.info(f"Provisioning {WORKERS} client certificate(s) for cert auth ...")
        _client_certs = provision_client_certs(WORKERS, _cert_dir, days=args.cert_days, prefix="dmload04")
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
