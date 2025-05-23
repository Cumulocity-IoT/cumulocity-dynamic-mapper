from threading import Thread
import queue
import paho.mqtt.client as mqtt_client
import logging
import os, time, random, json
from ratelimit import limits, sleep_and_retry
from datetime import datetime, timezone


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.DEBUG, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
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
client_id = f"python-mqtt-{random.randint(0, 10)}"

task_queue = queue.Queue()
event_count = 0


#### Define test
# parameter to control message format
EVENT_NUM = 1  ### Total number of events and meas; also the number of device
ARRAY_MESSAGE = True
# BATCH_NUM = 5000
BATCH_NUM = 100

# parameter to control load
TPS = 10  # TPS represents the maximum number of allowed publish operations within a specified time period. It effectively controls the rate at which messages can be published to MQTT topics.
TPS_PERIOD = 1 # TPS_PERIOD defines the time window (in seconds) during which the TPS limit applies.
WORKERS = 2
SLEEP_BETWEEN_ITERATIONS = 10

# functional parameter
diff_capid = True
capid_list = []  # ["TID-987654-1234567890", "TID-987654-1234567891"]
diff_event_type = True
# event types
event_type_list = ["geolocation", "gwCDMStatistics"]
diff_meas_type = True
device_num = EVENT_NUM  # Total number of devices


# ### Create record file
# record_name = f'{str(event_num)}-messages-{str(workers)}-workers-array-mes-record.json'
# if os.path.exists(f'./{record_name}'):
#     os.remove(f'./{record_name}')
# with open(record_name, 'w') as f:
#     f.write('[')
# # record = open(record_name, 'w')
# # record.write('[')


def create_capid(device_num):
    for i in range(1, device_num + 1):
        capid_list.append("TID-987654-" + str(i).zfill(10))


def connect_mqtt():
    def on_connect(client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print("Failed to connect, return code %d\n", rc)

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
    client.on_connect = on_connect
    client.connect(broker, port)
    return client


@sleep_and_retry
@limits(calls=TPS, period=TPS_PERIOD)
def publish(client, message, topic):
    global event_count
    result = client.publish(topic, message, qos=1)
    # result: [0, 1]
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
        "time": datetime.now(timezone.utc).isoformat()[:-3] + "Z",
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
                    "_time": datetime.now(timezone.utc).isoformat()[:-3] + "Z",
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
                datetime.now(timezone.utc).isoformat()[:-3] + "Z",
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
                    "_time": datetime.now(timezone.utc).isoformat()[:-3] + "Z",
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
                datetime.now(timezone.utc).isoformat()[:-3] + "Z",
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
        if task_queue.qsize() < BATCH_NUM / 10:
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


def consume_tasks(client):
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

        publish(client, payload, topic)
        task_queue.task_done()
        time.sleep(SLEEP_BETWEEN_ITERATIONS)


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
    client = connect_mqtt()
    logging.info("MQTT client created")
    ### Threads for publishing messages
    for n in range(WORKERS):
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
    # client.loop_forever()
    create_tasks()

    # time.sleep(3)
    # while not task_queue.empty():
    #     pass

    # client.loop_stop()

    # #### Finish writing records
    # with open(record_name, 'a') as f:
    #     f.write(']')
    # # record.write(']')
    # # record.close()


def main():
    try:
        create_capid(device_num)
        start_time = datetime.now(timezone.utc)
        run(start_time)
    except KeyboardInterrupt:
        print("Shutting down gracefully...")
        # Cleanup code
    finally:
        stop_time = datetime.now(timezone.utc).isoformat()[:-3] + "Z"
        print(f"Script stopped at {stop_time}")


if __name__ == "__main__":
    main()
