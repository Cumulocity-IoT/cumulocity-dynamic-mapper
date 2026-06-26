from threading import Thread, Lock
import queue
import paho.mqtt.client as mqtt_client
import logging
import os, time, random, json, signal, sys
from ratelimit import limits, sleep_and_retry
from datetime import datetime, timezone


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
EVENT_NUM = 10
QUEUE_SIZE = 5000

TPS = 1000
WORKERS = 20

capid_list = []
device_num = EVENT_NUM


def create_capid(n):
    for i in range(1, n + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt():
    def on_connect(client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print(f"Failed to connect, return code {rc}")

    client_id = f"python-mqtt-{random.randint(0, 10000)}"
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
    client.connect(broker, port)
    return client


@sleep_and_retry
@limits(calls=TPS, period=1)
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


def consume_tasks(client):
    while True:
        new_task = task_queue.get()
        topic = root_topic + geodict_topic
        publish(client, json.dumps(new_task), topic)
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
    for _ in range(WORKERS):
        client = connect_mqtt()
        t = Thread(target=consume_tasks, args=(client,))
        t.daemon = True
        t.start()
    logger.info(f"Started {WORKERS} publisher threads")

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
