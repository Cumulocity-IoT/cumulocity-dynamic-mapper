from threading import Thread
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
    level=logging.DEBUG, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger.info("Load test script started")


def parse_args():
    parser = argparse.ArgumentParser(
        description="MQTT inbound load test against the Cumulocity MQTT Service. "
                    "Publishes batched geolocation/statistics events at a target "
                    "aggregate TPS, spread across as many worker connections as "
                    "needed to stay under the broker's per-client rate cap.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    add_common_args(parser, default_total_tps=10)
    parser.add_argument(
        "--event-num", type=int, default=int(get_env("EVENT_NUM", 3)),
        help="Number of distinct simulated devices, also drives batch composition (env: EVENT_NUM)",
    )
    parser.add_argument(
        "--batch-num", type=int, default=int(get_env("BATCH_NUM", 100)),
        help="Messages grouped per published array batch (env: BATCH_NUM)",
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
password = get_env("MQTT_PASSWORD", "")

if args.auth == "password":
    logger.info(f"MQTT Configuration: broker={broker}, port={port}, username={username}")
else:
    c8y_tenant = resolve_tenant()
    logger.info(f"MQTT broker={broker}  port={port}  tenant={c8y_tenant}")
    logger.info("Auth: X.509 client certificate (one per worker connection)")

root_topic = "testmapper/"

task_queue = queue.Queue()
event_count = 0


#### Define test
# parameter to control message format
EVENT_NUM = args.event_num  #  total number of events and meas; also the number of device
ARRAY_MESSAGE = True
BATCH_NUM = args.batch_num

# parameter to control load
TOTAL_TPS = args.total_tps
MAX_TPS_PER_CLIENT = args.max_tps_per_client
WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_CLIENT)
TPS_PER_CLIENT = TOTAL_TPS / WORKERS

# Connect workers concurrently, bounded, instead of one at a time: a fully serial
# connect loop dilutes measured throughput with several seconds of ramp-up before
# the last worker ever publishes, while connecting all of them at once risks
# tripping broker-side connection rate-limiting.
CONNECT_CONCURRENCY = min(WORKERS, 5)

# functional parameter
diff_capid = True
capid_list = []  # ["TID-987654-1234567890", "TID-987654-1234567891"]
diff_event_type = True
# event types
event_type_list = ["geolocation", "gwCDMStatistics"]
diff_meas_type = True
device_num = EVENT_NUM  # Total number of devices

# Cert-auth state: one certificate per worker connection (clientId == cert CN).
_cert_dir = None
_client_certs = []


def create_capid(device_num):
    for i in range(1, device_num + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt(worker_index: int = 0):
    def on_connect(client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print("Failed to connect, return code %d\n", rc)

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
        if username and password and username.strip() != "" and password.strip() != "":
            client.username_pw_set(username, password)
            logger.info(f"Using authentication with username: {username}")
        else:
            logger.info("No authentication credentials provided, connecting anonymously")
        client.tls_set()

    client.tls_insecure_set(True)
    client.on_connect = on_connect
    client.connect(broker, port)
    # Without a running network loop, CONNACK/keepalive PINGREQ are never
    # processed and the broker will eventually drop the connection as idle.
    client.loop_start()
    return client


def publish(client, message, topic):
    global event_count
    result = client.publish(topic, message, qos=1)
    status = result[0]
    if status == 0:
        print(f"Send `{message}` to topic `{topic}`")
        event_count += 1
    else:
        print(f"Failed to send message to topic {topic}")


def create_payload(cap_id: str, event_type: str, meas_type: str):
    payload = {
        "version": "0",
        "id": cap_id,
        "detail-type": event_type,
        "source": "myapp.orders",
        "account": "123451235123",
        "time": datetime.now(timezone.utc).isoformat(),
        "region": "us-west-1",
        "detail": {
            "sensorAlternateId": cap_id,
            "capabilityAlternateId": event_type,
            "measures": [],
        },
    }
    if event_type == "geolocation":
        if meas_type == "dict":
            payload["detail"]["measures"] = [
                {
                    "latitude": random.uniform(-90, 90),
                    "longitude": random.uniform(-180, 180),
                    "elevation": random.uniform(0, 1000),
                    "accuracy": round(random.uniform(0, 10), 2),
                    "origin": "gps",
                    "gatewayidentifier": "TID-GWID-436521",
                    "_time": datetime.now(timezone.utc).isoformat(),
                }
            ]
        else:
            payload["detail"]["measures"] = [
                random.uniform(-90, 90),
                random.uniform(-180, 180),
                random.uniform(0, 1000),
                round(random.uniform(0, 10), 2),
                "gps",
                "TID-GWID-436521",
                datetime.now(timezone.utc).isoformat(),
            ]
    elif event_type == "gwCDMStatistics":
        if meas_type == "dict":
            payload["detail"]["measures"] = [
                {
                    "tmsDvcTot": random.randint(0, 1108972),
                    "cntApplicTot": random.randint(0, 6258),
                    "cntCldCnctsPerDay": random.randint(0, 50),
                    "enmCellTech": "lteCatM1",
                    "cntBattPlugged": random.randint(50, 500),
                    "cntBattLower10": random.randint(0, 20),
                    "isBattHealthy": "true",
                    "_time": datetime.now(timezone.utc).isoformat(),
                }
            ]
        else:
            payload["detail"]["measures"] = [
                random.randint(0, 1108972),
                random.randint(0, 6258),
                random.randint(0, 50),
                "lteCatM1",
                random.randint(50, 500),
                random.randint(0, 20),
                "true",
                datetime.now(timezone.utc).isoformat(),
            ]
    return payload


def create_mes_array(mes_array, message):
    if len(mes_array) != round(EVENT_NUM / BATCH_NUM):
        mes_array.append(message)
    else:
        logging.debug(f"Created an array with {EVENT_NUM / BATCH_NUM} messages")
        task_queue.put(mes_array)
        logging.info("Put a task")
        mes_array = []
        mes_array.append(message)
    return mes_array


def clear_mes_array(mes_array):
    logging.info("The last array")
    if mes_array:  # Only add to queue if the array is not empty
        task_queue.put(mes_array)
        logging.info("Put a task")
    else:
        logging.warning("Attempted to put empty array in queue, skipping...")
    mes_array = []


## def create_mes_array(mes_array, message):
# this is the task producer
def create_tasks():
    while True:
        if task_queue.qsize() >= BATCH_NUM / 10:
            # Back off instead of busy-spinning on qsize(): a tight spin loop here
            # would otherwise hog the GIL and starve the publisher threads of CPU time.
            time.sleep(0.005)
            continue

        mes_array_geo_dict = []
        mes_array_geo_array = []
        mes_array_static_dict = []
        mes_array_static_array = []
        for item in range(EVENT_NUM):
            if diff_capid:
                # tid = random.choice(capid_list)
                tid = capid_list[item]
            else:
                tid = "TID-987654-1234567890"
            if diff_event_type:
                event_type = random.choice(event_type_list)
            else:
                event_type = "geolocation"
            if diff_meas_type:
                meas_type = random.choice(["dict", "array"])
            else:
                meas_type = "array"
            if ARRAY_MESSAGE:
                message = create_payload(tid, event_type, meas_type)
                if event_type == "geolocation" and meas_type == "dict":
                    mes_array_geo_dict = create_mes_array(
                        mes_array_geo_dict, message
                    )
                elif event_type == "geolocation" and meas_type == "array":
                    mes_array_geo_array = create_mes_array(
                        mes_array_geo_array, message
                    )
                elif event_type == "gwCDMStatistics" and meas_type == "dict":
                    mes_array_static_dict = create_mes_array(
                        mes_array_static_dict, message
                    )
                elif event_type == "gwCDMStatistics" and meas_type == "array":
                    mes_array_static_array = create_mes_array(
                        mes_array_static_array, message
                    )
            else:
                message = create_payload(tid, event_type, meas_type)
                logging.debug("Created a message:")
                logging.debug(message)
                task_queue.put(message)
                logging.info("Put a task")
            if mes_array_geo_dict:
                clear_mes_array(mes_array_geo_dict)
            if mes_array_geo_array:
                clear_mes_array(mes_array_geo_array)
            if mes_array_static_dict:
                clear_mes_array(mes_array_static_dict)
            if mes_array_static_array:
                clear_mes_array(mes_array_static_array)


def consume_tasks(client, tps_per_client=TPS_PER_CLIENT):
    """Token-bucket / next-send-time scheduling: sleep only when ahead of schedule,
    so task_queue.get() latency never silently eats into the rate budget."""
    min_interval = 1.0 / tps_per_client if tps_per_client > 0 else 0
    next_send = time.monotonic()
    while True:
        new_task = task_queue.get()
        logging.info("Get one task")

        # Check if new_task is a list and not empty
        if isinstance(new_task, list):
            if not new_task:  # if list is empty
                logging.warning("Received empty list in task queue, skipping...")
                task_queue.task_done()
                continue
            exa_payload = new_task[0]
        else:
            exa_payload = new_task

        payload = json.dumps(new_task)

        if exa_payload["detail-type"] == "geolocation":
            if isinstance(exa_payload["detail"]["measures"][0], dict):
                topic = root_topic + "geodict"
                # just send first item form the new_task list
                payload = json.dumps(exa_payload)
            else:
                topic = root_topic + "geoarray"
        else:
            if isinstance(exa_payload["detail"]["measures"][0], dict):
                topic = root_topic + "gwdict"
                # just send first item form the new_task list
                payload = json.dumps(exa_payload)
            else:
                topic = root_topic + "gwarray"

        now = time.monotonic()
        if now < next_send:
            time.sleep(next_send - now)
        publish(client, payload, topic)
        next_send = max(time.monotonic(), next_send + min_interval)
        task_queue.task_done()


def _connect_worker(i):
    try:
        return connect_mqtt(worker_index=i)
    except Exception as e:
        logger.error(f"Skipping worker {i}: {e}")
        return None


def run():
    ### One dedicated MQTT connection per worker, each capped at TPS_PER_CLIENT.
    ### Connected concurrently (bounded) rather than one at a time, so the last
    ### worker doesn't come online several seconds after the first.
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
    logging.info(f"Started {connected}/{WORKERS} publisher threads (each a dedicated MQTT client, capped at {TPS_PER_CLIENT} TPS)")

    create_tasks()


def main():
    global _cert_dir, _client_certs

    create_capid(device_num)

    if args.auth == "cert":
        _cert_dir = tempfile.mkdtemp(prefix="dm-loadtest02-certs-")
        logger.info(f"Provisioning {WORKERS} client certificate(s) for cert auth ...")
        _client_certs = provision_client_certs(WORKERS, _cert_dir, days=args.cert_days, prefix="dmload02")
        logger.info(f"Provisioned {len(_client_certs)} client certificate(s).")

    def _cleanup():
        if args.auth == "cert" and _client_certs:
            logger.info("Cleaning up provisioned client certificates ...")
            cleanup_client_certs(_client_certs, _cert_dir)

    def _shutdown(sig, frame):
        print("Shutting down gracefully...")
        _cleanup()
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        run()
    except KeyboardInterrupt:
        print("Shutting down gracefully...")
    finally:
        stop_time = datetime.now(timezone.utc).isoformat()
        print(f"Script stopped at {stop_time}")
        _cleanup()


if __name__ == "__main__":
    main()
