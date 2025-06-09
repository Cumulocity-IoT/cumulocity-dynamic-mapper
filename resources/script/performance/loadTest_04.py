from threading import Thread
import queue
import paho.mqtt.client as mqtt_client
import logging
import os, time, random, json
from datetime import datetime, timezone
import threading


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

task_queue = queue.Queue()
message_create_count = 0
message_publish_count = 0

# Global variables for dynamic control
current_tps = 0
current_workers = 0
active_worker_threads = []

# Improved rate limiting using token bucket approach
class TokenBucket:
    def __init__(self):
        self.tokens = 0
        self.last_update = time.time()
        self.lock = threading.Lock()
    
    def consume_token(self, rate):
        """Try to consume a token. Returns True if successful, False if rate limit exceeded."""
        with self.lock:
            now = time.time()
            # Add tokens based on elapsed time and current rate
            elapsed = now - self.last_update
            self.tokens = min(rate, self.tokens + elapsed * rate)
            self.last_update = now
            
            if self.tokens >= 1:
                self.tokens -= 1
                return True
            return False
    
    def wait_for_token(self, rate):
        """Wait until a token is available."""
        while not self.consume_token(rate):
            # Calculate sleep time more precisely
            sleep_time = 1.0 / rate if rate > 0 else 0.1
            time.sleep(min(sleep_time, 0.01))  # Sleep at most 10ms at a time

# Global token bucket for rate limiting
token_bucket = TokenBucket()

#### Define test parameters
EVENT_NUM = 10  # total number of events and meas; also the number of device
ARRAY_MESSAGE = True
QUEUE_SIZE = 5000  # the size of the queue

# Load control parameters - TARGET VALUES
TARGET_TPS = 500  # Final target TPS
TARGET_WORKERS = 20  # Final target number of workers
SLEEP_BETWEEN_ITERATIONS = 0

# FADE-IN CONTROL PARAMETERS
TPS_RAMP_UP_INTERVAL = 45  # seconds between TPS increases
TPS_RAMP_UP_STEP = 100  # TPS increase per step
WORKER_RAMP_UP_INTERVAL = 30  # seconds between worker additions
WORKER_RAMP_UP_STEP = 4  # workers to add per step

# Starting values
INITIAL_TPS = 10  # Starting TPS
INITIAL_WORKERS = 2  # Starting number of workers

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


def publish(client, message, topic):
    global message_publish_count
    
    # Apply rate limiting using token bucket
    current_rate = current_tps if current_tps > 0 else 1
    token_bucket.wait_for_token(current_rate)
    
    result = client.publish(topic, message, qos=qos)
    # result: [0, 1]
    status = result[0]
    if status == 0:
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


def queue_tasks():
    """Task producer - produces tasks much faster than TPS to keep queue full"""
    while True:
        if task_queue.qsize() < QUEUE_SIZE:
            tid = random.choice(capid_list)
            event_type = "geolocation"
            message = create_payload(tid, event_type, "dict")

            global message_create_count
            message_create_count += 1
            
            logging.debug(message)
            task_queue.put(message)
            logging.debug("Put a task")
        else:
            # Queue is full, slow down task creation
            time.sleep(0.1)


def consume_tasks(client, worker_id):
    """Task consumer with worker ID for tracking"""
    logger.info(f"Worker {worker_id} started")
    try:
        while True:
            try:
                # Use timeout to allow graceful shutdown
                new_task = task_queue.get(timeout=1)
            except queue.Empty:
                continue
                
            logging.debug(f"Worker {worker_id} got one task")

            exa_payload = new_task
            payload = json.dumps(new_task)
            topic = root_topic + geodict_topic_code
            payload = json.dumps(exa_payload)
            
            # The rate limiting happens inside publish()
            publish(client, payload, topic)

            task_queue.task_done()
            
    except Exception as e:
        logger.error(f"Worker {worker_id} encountered error: {e}")
    finally:
        logger.info(f"Worker {worker_id} stopped")


def tps_ramp_up_controller(start_time):
    """Controls gradual TPS ramp-up"""
    global current_tps
    current_tps = INITIAL_TPS
    logger.info(f"TPS Controller started - Initial TPS: {current_tps}, Target TPS: {TARGET_TPS}")
    
    while current_tps < TARGET_TPS:
        time.sleep(TPS_RAMP_UP_INTERVAL)
        
        old_tps = current_tps
        current_tps = min(current_tps + TPS_RAMP_UP_STEP, TARGET_TPS)
        
        logger.info(f"TPS ramped up: {old_tps} -> {current_tps} (Target: {TARGET_TPS})")
        
        if current_tps >= TARGET_TPS:
            logger.info("TARGET TPS REACHED!")
            break
    
    # Keep running to maintain the TPS limit
    while True:
        time.sleep(60)  # Check every minute for potential adjustments


def worker_ramp_up_controller():
    """Controls gradual worker ramp-up"""
    global current_workers, active_worker_threads
    current_workers = 0
    logger.info(f"Worker Controller started - Target Workers: {TARGET_WORKERS}")
    
    # Start with initial workers
    for i in range(INITIAL_WORKERS):
        client = connect_mqtt()
        client.loop_start()  # Start the MQTT client loop
        worker_id = f"worker_{current_workers + 1}"
        t = Thread(target=consume_tasks, args=(client, worker_id))
        t.daemon = True
        t.start()
        active_worker_threads.append(t)
        current_workers += 1
        logger.info(f"Started {worker_id} - Total workers: {current_workers}")
        time.sleep(2)  # Small delay between worker starts
    
    # Gradually add more workers
    while current_workers < TARGET_WORKERS:
        time.sleep(WORKER_RAMP_UP_INTERVAL)
        
        workers_to_add = min(WORKER_RAMP_UP_STEP, TARGET_WORKERS - current_workers)
        
        for i in range(workers_to_add):
            client = connect_mqtt()
            client.loop_start()  # Start the MQTT client loop
            worker_id = f"worker_{current_workers + 1}"
            t = Thread(target=consume_tasks, args=(client, worker_id))
            t.daemon = True
            t.start()
            active_worker_threads.append(t)
            current_workers += 1
            logger.info(f"Added {worker_id} - Total workers: {current_workers}/{TARGET_WORKERS}")
            time.sleep(2)  # Small delay between worker starts
        
        if current_workers >= TARGET_WORKERS:
            logger.info("TARGET WORKERS REACHED!")
            break


def monitoring_thread():
    """Enhanced monitoring with ramp-up progress and TPS efficiency"""
    last_published = 0
    last_time = time.time()
    
    while True:
        time.sleep(10)  # Report every 10 seconds
        
        current_time = time.time()
        time_diff = current_time - last_time
        published_diff = message_publish_count - last_published
        actual_tps = published_diff / time_diff if time_diff > 0 else 0
        
        tps_progress = (current_tps / TARGET_TPS) * 100 if TARGET_TPS > 0 else 0
        worker_progress = (current_workers / TARGET_WORKERS) * 100 if TARGET_WORKERS > 0 else 0
        tps_efficiency = (actual_tps / current_tps) * 100 if current_tps > 0 else 0
        
        logger.info("=" * 80)
        logger.info("LOAD TEST STATUS:")
        logger.info(f"  Messages Created: {message_create_count}")
        logger.info(f"  Messages Published: {message_publish_count}")
        logger.info(f"  Queue Size: {task_queue.qsize()}")
        logger.info(f"  Target TPS: {current_tps}/{TARGET_TPS} ({tps_progress:.1f}%)")
        logger.info(f"  Actual TPS: {actual_tps:.1f} (Efficiency: {tps_efficiency:.1f}%)")
        logger.info(f"  Current Workers: {current_workers}/{TARGET_WORKERS} ({worker_progress:.1f}%)")
        logger.info(f"  Active Threads: {len(active_worker_threads)}")
        logger.info(f"  Token Bucket Tokens: {token_bucket.tokens:.2f}")
        
        if current_tps >= TARGET_TPS and current_workers >= TARGET_WORKERS:
            if tps_efficiency >= 90:
                logger.info("  STATUS: FULL LOAD REACHED! 🚀")
            else:
                logger.info("  STATUS: FULL LOAD REACHED BUT LOW EFFICIENCY ⚠️")
        else:
            logger.info("  STATUS: RAMPING UP... ⬆️")
        logger.info("=" * 80)
        
        # Update for next iteration
        last_published = message_publish_count
        last_time = current_time


def run(start_time):
    logging.info("Starting MQTT load test with gradual ramp-up")
    logging.info(f"Configuration:")
    logging.info(f"  Target TPS: {INITIAL_TPS} -> {TARGET_TPS} (step: {TPS_RAMP_UP_STEP} every {TPS_RAMP_UP_INTERVAL}s)")
    logging.info(f"  Target Workers: {INITIAL_WORKERS} -> {TARGET_WORKERS} (step: {WORKER_RAMP_UP_STEP} every {WORKER_RAMP_UP_INTERVAL}s)")
    
    # Start TPS ramp-up controller
    t_tps_controller = Thread(target=tps_ramp_up_controller, args=(start_time,))
    t_tps_controller.daemon = True
    t_tps_controller.start()
    logger.info("TPS ramp-up controller started")
    
    # Start worker ramp-up controller
    t_worker_controller = Thread(target=worker_ramp_up_controller)
    t_worker_controller.daemon = True
    t_worker_controller.start()
    logger.info("Worker ramp-up controller started")
    
    # Start task queue producer(s) - multiple producers to keep queue full
    for i in range(2):  # Use 2 producers to ensure queue stays full
        t = Thread(target=queue_tasks)
        t.daemon = True
        t.start()
    logger.info("Task producers started")
    
    # Start monitoring
    t_monitor = Thread(target=monitoring_thread)
    t_monitor.daemon = True
    t_monitor.start()
    logger.info("Monitoring thread started")
    
    # Keep main thread alive
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        logger.info("Received shutdown signal")
        raise


def main():
    try:
        create_capid(device_num)
        start_time = datetime.now(timezone.utc)
        logger.info(f"Load test started at {start_time.isoformat()}")
        run(start_time)
    except KeyboardInterrupt:
        print("Shutting down gracefully...")
        logger.info("Shutdown initiated by user")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
    finally:
        stop_time = datetime.now(timezone.utc).isoformat()
        print(f"Script stopped at {stop_time}")
        logger.info(f"Final stats: Workers: {current_workers}, TPS: {current_tps}")
        logger.info(f"Total messages - Created: {message_create_count}, Published: {message_publish_count}")


if __name__ == "__main__":
    main()