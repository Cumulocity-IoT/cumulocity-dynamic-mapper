from threading import Thread
import queue
import paho.mqtt.client as mqtt_client
import logging
import sys, os, time, random, json
from datetime import datetime
from ratelimit import limits, sleep_and_retry

logger = logging.getLogger('')
logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger.info('Logger was initialized')

broker = '<ANY_TENANT>.cumulocity.com'
port = 9883
root_topic = "testmapper/"
client_id = f'python-mqtt-{random.randint(0, 10)}'
username = ''
password = ''

task_queue = queue.Queue()
event_count = 0


#### Define test
event_num = 100000 ### Total number of events and meas; also the number of device
array_message = True
batch_num = 5000
diff_capid = True
capid_list = [] #["TID-987654-1234567890", "TID-987654-1234567891"]
diff_event_type = True
event_type_list = ["geolocation", "gwCDMStatistics"]
diff_meas_type = True
workers = 2
interval = 0
device_num = event_num #### Total number of devices
TPS = 50
TPS_PEROID = 1
#####

# ### Create record file
# record_name = f'{str(event_num)}-messages-{str(workers)}-workers-array-mes-record.json'
# if os.path.exists(f'./{record_name}'):
#     os.remove(f'./{record_name}')
# with open(record_name, 'w') as f:
#     f.write('[')
# # record = open(record_name, 'w')
# # record.write('[')


def create_capid(device_num):
    for i in range(1, device_num+1):
        capid_list.append("TID-987654-"+ str(i).zfill(10))


def connect_mqtt():
    def on_connect(client, userdata, flags, rc):
        if rc == 0:
            print("Connected to MQTT Service!")
        else:
            print("Failed to connect, return code %d\n", rc)

    client = mqtt_client.Client(client_id)
    client.username_pw_set(username, password)
    client.tls_set(ca_certs='gdroot-g2.crt')
    client.tls_insecure_set(True)
    client.on_connect = on_connect
    client.connect(broker, port)
    return client


@sleep_and_retry
@limits(calls=TPS, period=TPS_PEROID)
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
        "time": datetime.utcnow().isoformat()[:-3] + 'Z',
        "region": "us-west-1",
        "detail": {
            "sensorAlternateId": cap_id,
            "capabilityAlternateId": event_type,
            "measures": []
        }
    }
    if event_type == "geolocation":
        if meas_type == 'dict':
            payload["detail"]["measures"] = [{
                "latitude": random.uniform(-90, 90),
                "longitude": random.uniform(-180, 180),
                "elevation": random.uniform(0, 1000),
                "accuracy": round(random.uniform(0, 10), 2),
                "origin": "gps",
                "gatewayidentifier": "TID-GWID-436521",
                "_time": datetime.utcnow().isoformat()[:-3] + 'Z'
            }]
        else:
            payload["detail"]["measures"] = [
                random.uniform(-90, 90),
                random.uniform(-180, 180),
                random.uniform(0, 1000),
                round(random.uniform(0, 10), 2),
                "gps",
                "TID-GWID-436521",
                datetime.utcnow().isoformat()[:-3] + 'Z'
            ]
    elif event_type == "gwCDMStatistics":
        if meas_type == 'dict':
            payload["detail"]["measures"] = [{
                "tmsDvcTot": random.randint(0, 1108972),
                "cntApplicTot": random.randint(0, 6258),
                "cntCldCnctsPerDay": random.randint(0, 50),
                "enmCellTech": "lteCatM1",
                "cntBattPlugged": random.randint(50, 500),
                "cntBattLower10": random.randint(0, 20),
                "isBattHealthy": "true",
                "_time": datetime.utcnow().isoformat()[:-3] + 'Z'
            }]
        else:
            payload["detail"]["measures"] = [
                random.randint(0, 1108972),
                random.randint(0, 6258),
                random.randint(0, 50),
                "lteCatM1",
                random.randint(50, 500),
                random.randint(0, 20),
                "true",
                datetime.utcnow().isoformat()[:-3] + 'Z'
            ]
    return payload


def create_mes_arry(mes_array, message):
    if len(mes_array) != round(event_num / batch_num):
        mes_array.append(message)
    else:
        logging.debug(f'Created an array with {event_num / batch_num} messages')
        task_queue.put(mes_array)
        logging.info('Put a task')
        mes_array = []
        mes_array.append(message)
    return mes_array


def clear_mes_arry(mes_array):
    logging.info('The last array ')
    task_queue.put(mes_array)
    logging.info('Put a task')
    mes_array = []
    # return mes_array


def create_tasks():
    while True:
        if task_queue.qsize() < batch_num/10:
            mes_array_geo_dict = []
            mes_array_geo_array = []
            mes_array_static_dict = []
            mes_array_static_array = []
            for item in range(event_num):
                if diff_capid:
                    #tid = random.choice(capid_list)
                    tid = capid_list[item]
                else:
                    tid = "TID-987654-1234567890"
                if diff_event_type:
                    event_type = random.choice(event_type_list)
                else:
                    event_type = "geolocation"
                if diff_meas_type:
                    meas_type = random.choice(['dict', 'array'])
                else:
                    meas_type = 'array'
                if array_message:
                    message = create_payload(tid, event_type, meas_type)
                    if event_type == 'geolocation' and meas_type == 'dict':
                        mes_array_geo_dict= create_mes_arry(mes_array_geo_dict, message)
                    elif event_type == 'geolocation' and meas_type == 'array':
                        mes_array_geo_array = create_mes_arry(mes_array_geo_array, message)
                    elif event_type == 'gwCDMStatistics' and meas_type == 'dict':
                        mes_array_static_dict = create_mes_arry(mes_array_static_dict, message)
                    elif event_type == 'gwCDMStatistics' and meas_type == 'array':
                        mes_array_static_array = create_mes_arry(mes_array_static_array, message)
                else:
                    message = create_payload(tid, event_type, meas_type)
                    logging.debug('Created a message:')
                    logging.debug(message)
                    task_queue.put(message)
                    logging.info('Put a task')
            if mes_array_geo_dict is not []:
                clear_mes_arry(mes_array_geo_dict)
            if mes_array_geo_array is not []:
                clear_mes_arry(mes_array_geo_array)
            if mes_array_static_dict is not []:
                clear_mes_arry(mes_array_static_dict)
            if mes_array_static_array is not []:
                clear_mes_arry(mes_array_static_array)



def consume_tasks(client):
    while True:
        new_task = task_queue.get()
        payload = json.dumps(new_task)
        logging.info('Get one task')

        # #### Write record
        # with open(record_name, 'a') as f:
        #     json.dump(new_task, f)
        #     f.write(',')


        # json.dump(new_task, record)
        # # record.write(payload)
        # record.write(',')
        # logging.info(payload)
        if type(new_task) == list:
            exa_payload = new_task[0]
        else:
            exa_payload = new_task
        if exa_payload['detail-type'] == 'geolocation':
            if type(exa_payload['detail']['measures'][0]) != dict:
                topic = root_topic + 'geoarray'
            else:
                topic = root_topic + 'geodict'
        else:
            if type(exa_payload['detail']['measures'][0]) != dict:
                topic = root_topic + 'gwarray'
            else:
                topic = root_topic + 'gwdict'
        publish(client, payload, topic)
        task_queue.task_done()
        time.sleep(interval)


def tps_timer(start_time):
    while True:
        now_time = datetime.utcnow()
        diff_time = now_time - start_time
        if diff_time.total_seconds() > 12*60*60:
            global TPS
            TPS = TPS + 10
        else:
            time.sleep(3600)


def run(start_time):
    client = connect_mqtt()
    logging.info('MQTT client created')
    ### Threads for publishing messages
    for n in range(workers):
        t = Thread(target=consume_tasks, args=(client,))
        t.daemon = True
        t.start()
    logging.info('Publisher threads created')

    ### Thread for timer
    t_timer = Thread(target=tps_timer, args=(start_time,))
    t_timer.daemon = True
    t_timer.start()
    logging.info('Timer thread created')

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


if __name__ == '__main__':
    create_capid(device_num)
    start_time= datetime.utcnow()
    run(start_time)
    start_time_iso = start_time.isoformat()[:-3] + 'Z'
    print(f'Script started at {start_time_iso}')
    stop_time = datetime.utcnow().isoformat()[:-3] + 'Z'
    print(f'Script stopped at {stop_time}')
