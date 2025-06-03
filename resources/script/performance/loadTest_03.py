from threading import Thread
import queue
import paho.mqtt.client as mqtt_client
import logging
import os, time, random, json
from ratelimit import limits, sleep_and_retry
from datetime import datetime, timezone


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger.info("Load test script started")


# Helper function to get environment variable with a default value
def get_env(key, default=None):
    return os.environ.get(key, default)


# Set broker from environment variable
# Priority: MQTT_BROKER > C8Y_DOMAIN > default
broker = get_env("MQTT_BROKER")
if not broker:
    # Fall back to C8Y_DOMAIN if available
    c8y_domain = get_env("C8Y_DOMAIN")
    if c8y_domain:
        broker = c8y_domain
    else:
        broker = "broker.emqx.io"  # Default value

# Set port from environment variable or use default
try:
    port = int(get_env("MQTT_PORT", 9883))
except (ValueError, TypeError):
    # If MQTT_PORT exists but is not a valid integer
    port = 9883

# Set username from environment variables
# Priority: USERNAME > C8Y_TENANT/C8Y_USERNAME
username = get_env("USERNAME")
if not username:
    c8y_tenant = get_env("C8Y_TENANT")
    c8y_username = get_env("C8Y_USERNAME")
    if c8y_tenant and c8y_username:
        username = f"{c8y_tenant}/{c8y_username}"
    else:
        username = ""  # Default value

# Set password from environment variable
password = get_env("PASSWORD", "")  # Default to empty string

# Log the configuration (without showing the password)
logger.info(f"MQTT Configuration: broker={broker}, port={port}, username={username}")
if password:
    logger.info("Password is set")
else:
    logger.info("Password is not set")

root_topic = "testmapper/"
geodict_topic_code = "geodictCode"
geodict_topic = "geodict"
qos = 0

#client_id = f"python-mqtt-{random.randint(0, 1000)}"

task_queue = queue.Queue()
message_create_count = 0
message_publish_count = 0


#### Define test
# parameter to control message format
EVENT_NUM = 10  #  total number of events and meas; also the number of device
ARRAY_MESSAGE = True
QUEUE_SIZE = 5000  # the size of the queue

# parameter to control load
TPS = 500# TPS represents the maximum number of allowed publish operations within a specified time period. It effectively controls the rate at which messages can be published to MQTT topics.
WORKERS = 20
SLEEP_BETWEEN_ITERATIONS = 0

# functional parameter
diff_capid = True
capid_list = []  # ["TID-987654-1234567890", "TID-987654-1234567891"]
diff_event_type = True
# event types
event_type_list = ["geolocation", "gwCDMStatistics"]
diff_meas_type = True
device_num = EVENT_NUM  # Total number of devices


def create_capid(device_num):
    for i in range(1, device_num + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt():
    def on_connect(client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print("Failed to connect, return code %d\n", rc)
    client_id = f"python-mqtt-{random.randint(0, 10000)}"
    client = mqtt_client.Client(
        client_id=client_id,
        callback_api_version=mqtt_client.CallbackAPIVersion.VERSION2,
    )
    # Only set username and password if both are provided and non-empty
    if username and password and username.strip() != "" and password.strip() != "":
        client.username_pw_set(username, password)
        logger.info(f"Using authentication with username: {username}")
    else:
        logger.info("No authentication credentials provided, connecting anonymously")
    # client.tls_set(ca_certs="gdroot-g2.crt")
    client.tls_set()
    client.tls_insecure_set(True)
    client.clean_session = True
    client.on_connect = on_connect
    client.connect(broker, port)
    return client


@sleep_and_retry
@limits(calls=TPS, period=1)
def publish(client, message, topic):
    global message_publish_count
    result = client.publish(topic, message, qos=qos)
    # result: [0, 1]
    status = result[0]
    if status == 0:
        #print(f"Send `{message}` to topic `{topic}`")
        message_publish_count += 1
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

    return payload


## def create_mes_array(mes_array, message):
# this is the task producer
def queue_tasks():
    while True:
        if task_queue.qsize() < QUEUE_SIZE:
            tid = random.choice(capid_list)
            event_type = "geolocation"
            message = create_payload(tid, event_type, "dict")

            global message_create_count
            message_create_count += 1
            
           #logging.info("Queue message: " + str(message_create_count))
            logging.debug(message)
            task_queue.put(message)
            logging.debug("Put a task")


def consume_tasks(client):
    while True:
        new_task = task_queue.get()
        logging.debug("Get one task")

        exa_payload = new_task

        payload = json.dumps(new_task)

        topic = root_topic + geodict_topic_code
        # just send first item form the new_task list
        payload = json.dumps(exa_payload)
        publish(client, payload, topic)

        task_queue.task_done()
        #time.sleep(SLEEP_BETWEEN_ITERATIONS)


def tps_timer(start_time):
    while True:
        now_time = datetime.now(timezone.utc)
        diff_time = now_time - start_time
        if diff_time.total_seconds() > 12 * 60 * 60:
            global TPS
            TPS = TPS + 10
        else:
            time.sleep(3600)


def run(start_time):
    logging.info("MQTT client created")
    ### Threads for publishing messages
    for n in range(WORKERS):
        client = connect_mqtt()
        t = Thread(target=consume_tasks, args=(client,))
        t.daemon = True
        t.start()
    logging.info("Publisher threads created")

    ### Thread for timer
    t_timer = Thread(target=tps_timer, args=(start_time,))
    t_timer.daemon = True
    t_timer.start()
    logging.info("Timer thread created")

    client.loop_start()
    for i in range(1):
        t = Thread(target=queue_tasks)
        t.daemon = True
        t.start()
    while True:
        time.sleep(1)
        logging.info(f"Message created: {message_create_count}")
        logging.info(f"Message published: {message_publish_count}")
        logging.info(f"Queue size: {task_queue.qsize()}")

def main():
    try:
        create_capid(device_num)
        start_time = datetime.now(timezone.utc)
        run(start_time)
    except KeyboardInterrupt:
        print("Shutting down gracefully...")
        # Cleanup code
    finally:
        stop_time = datetime.now(timezone.utc).isoformat()
        print(f"Script stopped at {stop_time}")


if __name__ == "__main__":
    main()
