from threading import Thread, Lock, Event
import queue
import uuid
import paho.mqtt.client as mqtt_client
import logging
import os, time, random, json, signal, sys, math
from datetime import timezone


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger.info("Load test script started")


def get_env(key, default=None):
    return os.environ.get(key, default)


# Broker: explicit override > C8Y_DOMAIN > fallback
broker = get_env("MQTT_BROKER") or get_env("C8Y_DOMAIN") or "broker.emqx.io"

try:
    port = int(get_env("MQTT_PORT", 9883))
except (ValueError, TypeError):
    port = 9883

# Username: explicit override > C8Y_TENANT/C8Y_USERNAME
c8y_tenant = get_env("C8Y_TENANT", "")
c8y_username = get_env("C8Y_USERNAME") or get_env("C8Y_USER", "")
username = get_env("MQTT_USERNAME") or (
    f"{c8y_tenant}/{c8y_username}" if c8y_tenant and c8y_username else ""
)

# Password: explicit override > raw JWT from C8Y_HEADER_AUTHORIZATION (strip "Bearer ")
_auth_header = get_env("C8Y_HEADER_AUTHORIZATION", "")
_jwt_token = _auth_header.removeprefix("Bearer ").strip()
password = get_env("MQTT_PASSWORD") or _jwt_token

if not username:
    raise SystemExit("ERROR: MQTT username could not be determined. Set C8Y_TENANT + C8Y_USERNAME or MQTT_USERNAME.")
if not password:
    raise SystemExit("ERROR: MQTT password could not be determined. Set C8Y_HEADER_AUTHORIZATION or MQTT_PASSWORD.")

logger.info(f"MQTT broker={broker}  port={port}  username={username}")
logger.info(f"Auth: {'JWT token (Bearer)' if _jwt_token else 'MQTT_PASSWORD env var'}")

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
EVENT_NUM = 10
QUEUE_SIZE = 5000

# Target aggregate throughput. WORKERS and TPS_PER_CLIENT are derived automatically.
# The broker enforces a hard limit of 100 msg/s per MQTT client; MAX_TPS_PER_CLIENT
# stays slightly below that to give headroom.
TOTAL_TPS = 10
MAX_TPS_PER_CLIENT = 90

WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_CLIENT)
TPS_PER_CLIENT = TOTAL_TPS / WORKERS

# How long (seconds) to wait for each client to connect before giving up
CONNECT_TIMEOUT = 15
# Stagger between worker connection attempts to avoid broker rate-limiting
CONNECT_STAGGER_S = 0.3

message_type = ["telemetry", "error"]
capid_list = []
device_num = EVENT_NUM


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

    client_id = f"python-mqtt-{worker_index}-{random.randint(0, 10000)}"
    client = mqtt_client.Client(
        client_id=client_id,
        callback_api_version=mqtt_client.CallbackAPIVersion.VERSION2,
    )
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
    elapsed = time.time() - start_time
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


def run(start_time):
    for i in range(WORKERS):
        if i > 0:
            time.sleep(CONNECT_STAGGER_S)
        try:
            client = connect_mqtt(worker_index=i)
        except RuntimeError as e:
            logger.error(f"Skipping worker {i}: {e}")
            continue
        t = Thread(target=consume_tasks, args=(client,))
        t.daemon = True
        t.start()
    logger.info(f"Started {WORKERS} publisher threads (each a dedicated MQTT client, capped at {TPS_PER_CLIENT} TPS)")

    producer = Thread(target=queue_tasks)
    producer.daemon = True
    producer.start()
    logger.info("Producer thread started")

    while True:
        time.sleep(1)
        created, published, failed = snapshot_counters()
        logger.info(f"created={created} published={published} failed={failed} queue={task_queue.qsize()}")


def main():
    create_capid(device_num)
    start_time = time.time()

    def _shutdown(sig, frame):
        print("\nShutting down gracefully...")
        print_stats(start_time)
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        run(start_time)
    finally:
        print_stats(start_time)


if __name__ == "__main__":
    main()
