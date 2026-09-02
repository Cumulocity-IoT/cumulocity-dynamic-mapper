from threading import Thread, Lock, Event, local
import queue
import uuid
import logging
import subprocess
import os, time, random, json, signal, sys, math
from datetime import datetime, timezone
from urllib.parse import urlsplit
import http.client


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
active_processes = set()
_thread_local = local()


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
TOTAL_TPS = 250
MAX_TPS_PER_WORKER = 50
# HTTP API calls are much cheaper than spawning the c8y CLI, so more concurrent
# worker threads (each with its own persistent connection) than the pure
# TOTAL_TPS/MAX_TPS_PER_WORKER split can still push more throughput in practice.
MIN_WORKERS = 20
WORKERS = max(math.ceil(TOTAL_TPS / MAX_TPS_PER_WORKER), MIN_WORKERS)
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


def _resolve_base_url():
    base_url = get_env("C8Y_BASEURL") or get_env("C8Y_URL") or get_env("C8Y_DOMAIN")
    if not base_url:
        raise SystemExit("ERROR: Missing C8Y_BASEURL/C8Y_URL/C8Y_DOMAIN for HTTP API mode.")
    if not base_url.startswith("http://") and not base_url.startswith("https://"):
        base_url = f"https://{base_url}"
    return base_url.rstrip("/")


def _resolve_auth_header():
    auth_header = get_env("C8Y_HEADER_AUTHORIZATION", "").strip()
    if auth_header:
        return auth_header

    tenant = get_env("C8Y_TENANT", "").strip()
    username = (get_env("C8Y_USERNAME") or get_env("C8Y_USER") or "").strip()
    password = get_env("C8Y_PASSWORD", "").strip()
    if tenant and username and password:
        import base64

        token = base64.b64encode(f"{tenant}/{username}:{password}".encode("utf-8")).decode("ascii")
        return f"Basic {token}"

    raise SystemExit(
        "ERROR: Missing auth for HTTP API mode. Set C8Y_HEADER_AUTHORIZATION or C8Y_TENANT+C8Y_USERNAME+C8Y_PASSWORD."
    )


def _get_thread_connection():
    """
    Each worker thread keeps its own persistent (keep-alive) HTTP(S) connection,
    so requests don't pay TCP/TLS setup cost per message and threads never
    contend on a shared socket/opener.
    """
    conn = getattr(_thread_local, "conn", None)
    if conn is not None:
        return conn
    split = urlsplit(BASE_URL)
    if split.scheme == "https":
        conn = http.client.HTTPSConnection(split.hostname, split.port or 443, timeout=10)
    else:
        conn = http.client.HTTPConnection(split.hostname, split.port or 80, timeout=10)
    _thread_local.conn = conn
    return conn


def _reset_thread_connection():
    conn = getattr(_thread_local, "conn", None)
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass
        _thread_local.conn = None


def _post_json(path, payload):
    body = json.dumps(payload).encode("utf-8")
    headers = {
        "Authorization": AUTH_HEADER,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    conn = _get_thread_connection()
    try:
        conn.request("POST", path, body=body, headers=headers)
        resp = conn.getresponse()
        status = resp.status
        resp.read()  # drain response so the keep-alive connection can be reused
        return status
    except Exception:
        # Connection may have gone stale (broker closed keep-alive, network blip, etc).
        # Reset and retry once with a fresh connection before giving up.
        _reset_thread_connection()
        conn = _get_thread_connection()
        conn.request("POST", path, body=body, headers=headers)
        resp = conn.getresponse()
        status = resp.status
        resp.read()
        return status


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
    internal_id = device_id_map[payload["clientId"]]
    now_iso = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    if payload["payloadType"] == "telemetry":
        body = {
            "source": {"id": internal_id},
            "type": "c8y_TemperatureMeasurement",
            "time": now_iso,
            "c8y_Steam": {"Temperature": {"unit": "C", "value": payload["sensorData"]["temp_val"]}},
        }
        path = "/measurement/measurements"
    else:
        body = {
            "source": {"id": internal_id},
            "type": "c8y_ErrorEvent",
            "time": now_iso,
            "text": payload["logMessage"],
            "severity": "MAJOR",
            "status": "ACTIVE",
        }
        path = "/event/events"

    if stop_event.is_set():
        return
    try:
        status = _post_json(path, body)
    except Exception as ex:
        inc_failed()
        logger.error(f"c8y http publish failed: {ex}")
        return

    if 200 <= status < 300:
        inc_published()
    else:
        inc_failed()
        logger.error(f"c8y http publish failed: status={status}")


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
        try:
            publish_via_c8y(new_task)
        finally:
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
    global BASE_URL, AUTH_HEADER
    create_capid(device_num)
    BASE_URL = _resolve_base_url()
    AUTH_HEADER = _resolve_auth_header()
    def _shutdown(sig, frame):
        logger.info("Shutdown signal received. Stopping ...")
        stop_event.set()
        with active_process_lock:
            for process in list(active_processes):
                if process.poll() is None:
                    process.terminate()

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
