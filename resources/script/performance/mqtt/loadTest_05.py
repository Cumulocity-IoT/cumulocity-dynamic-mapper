from threading import Thread, Lock, Event
import queue
import uuid
import logging
import subprocess
import os, time, random, json, signal, sys, math


logger = logging.getLogger("")
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger.info("Outbound C8Y ingestion load test started")


def get_env(key, default=None):
    return os.environ.get(key, default)


task_queue = queue.Queue()

_counter_lock = Lock()
_message_create_count = 0
_message_publish_count = 0
_message_fail_count = 0

stop_event = Event()
active_process_lock = Lock()
active_process = None


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
TOTAL_TPS = 5
MAX_TPS_PER_WORKER = 50
WORKERS = math.ceil(TOTAL_TPS / MAX_TPS_PER_WORKER)
TPS_PER_WORKER = TOTAL_TPS / WORKERS

message_type = ["telemetry", "error"]
capid_list = []
device_id_map = {}
device_num = EVENT_NUM


def create_capid(n):
    for i in range(1, n + 1):
        capid_list.append(str(i).zfill(10))


def run_c8y_command(cmd):
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(cmd)}\n{result.stderr.strip()}")
    return result.stdout


def ensure_devices():
    for serial in capid_list:
        if stop_event.is_set():
            return
        identity_cmd = [
            "c8y",
            "identity",
            "get",
            "--type",
            "c8y_Serial",
            "--name",
            serial,
            "-o",
            "json",
            "--compact",
        ]
        identity_result = subprocess.run(identity_cmd, capture_output=True, text=True)
        if identity_result.returncode == 0:
            identity = json.loads(identity_result.stdout)
            device_id_map[serial] = identity["managedObject"]["id"]
            continue

        create_cmd = [
            "c8y",
            "devices",
            "create",
            "--force",
            "--name",
            f"outbound-perf-{serial}",
            "--type",
            "c8y_GeneratedDeviceType",
            "--data",
            json.dumps({"c8y_Serial": serial}),
            "-o",
            "json",
            "--compact",
        ]
        created = json.loads(run_c8y_command(create_cmd))
        internal_id = created["id"]
        identity_create_cmd = [
            "c8y",
            "identity",
            "create",
            "--force",
            "--type",
            "c8y_Serial",
            "--name",
            serial,
            "--id",
            internal_id,
            "-o",
            "json",
            "--compact",
        ]
        run_c8y_command(identity_create_cmd)
        device_id_map[serial] = internal_id


def create_payload(cap_id: str):
    selected_type = random.choice(message_type)
    if selected_type == "telemetry":
        return {
            "messageId": str(uuid.uuid4()),
            "clientId": cap_id,
            "payloadType": "telemetry",
            "sensorData": {
                "temp_val": random.uniform(-20, 35),
            },
        }
    return {
        "messageId": str(uuid.uuid4()),
        "clientId": cap_id,
        "payloadType": "error",
        "logMessage": "Sensor malfunction detected",
    }


def queue_tasks():
    while not stop_event.is_set():
        if task_queue.qsize() < QUEUE_SIZE:
            tid = random.choice(capid_list)
            task_queue.put(create_payload(tid))
            inc_created()
        else:
            time.sleep(0.005)


def publish_via_c8y(payload):
    global active_process
    internal_id = device_id_map[payload["clientId"]]
    if payload["payloadType"] == "telemetry":
        measurement_data = {"c8y_Steam": {"Temperature": {"unit": "C", "value": payload["sensorData"]["temp_val"]}}}
        cmd = [
            "c8y",
            "measurements",
            "create",
            "--force",
            "--device",
            internal_id,
            "--type",
            "c8y_TemperatureMeasurement",
            "--time",
            time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
            "--data",
            json.dumps(measurement_data),
        ]
    else:
        event_data = {"severity": "MAJOR", "status": "ACTIVE"}
        cmd = [
            "c8y",
            "events",
            "create",
            "--force",
            "--device",
            internal_id,
            "--type",
            "c8y_ErrorEvent",
            "--time",
            time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
            "--text",
            payload["logMessage"],
            "--data",
            json.dumps(event_data),
        ]

    with active_process_lock:
        if stop_event.is_set():
            return
        active_process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    stdout, stderr = active_process.communicate()
    return_code = active_process.returncode
    with active_process_lock:
        active_process = None
    if return_code == 0:
        inc_published()
    else:
        inc_failed()
        logger.error(f"c8y cli publish failed: {stderr.strip()}")


def consume_tasks(tps_per_worker=TPS_PER_WORKER):
    min_interval = 1.0 / tps_per_worker if tps_per_worker > 0 else 0
    next_send = time.monotonic()
    while not stop_event.is_set():
        try:
            new_task = task_queue.get(timeout=0.2)
        except queue.Empty:
            continue
        now = time.monotonic()
        if now < next_send:
            time.sleep(next_send - now)
        publish_via_c8y(new_task)
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
    workers = []
    for i in range(WORKERS):
        t = Thread(target=consume_tasks)
        t.daemon = True
        t.start()
        workers.append(t)
    logger.info(f"Started {WORKERS} c8y publisher threads (capped at {TPS_PER_WORKER} TPS each)")

    producer = Thread(target=queue_tasks)
    producer.daemon = True
    producer.start()
    logger.info("Producer thread started")

    last_published = 0
    last_tick = time.monotonic()
    while not stop_event.is_set():
        if stop_event.wait(1.0):
            break
        now = time.monotonic()
        created, published, failed = snapshot_counters()
        interval = max(now - last_tick, 1e-9)
        sent_rate = (published - last_published) / interval
        logger.info(
            f"created={created} sent_ok={published} failed={failed} queue={task_queue.qsize()} sent_rate={sent_rate:.1f}/s"
        )
        last_published = published
        last_tick = now

    producer.join(timeout=1)
    for w in workers:
        w.join(timeout=1)


def main():
    create_capid(device_num)
    def _shutdown(sig, frame):
        logger.info("Shutdown signal received. Stopping ...")
        stop_event.set()
        with active_process_lock:
            if active_process is not None and active_process.poll() is None:
                active_process.terminate()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    ensure_devices()
    start_time = time.time()

    try:
        run(start_time)
    finally:
        print_stats(start_time)


if __name__ == "__main__":
    main()
