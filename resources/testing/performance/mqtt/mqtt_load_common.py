"""Shared helpers for the MQTT performance load-test scripts (loadTest_0*.py).

Two responsibilities:
  - `add_common_args`: the throughput/broker/auth CLI options shared by every
    script, each falling back to an environment variable so existing shell
    invocations keep working unchanged.
  - X.509 client-certificate provisioning for Cumulocity MQTT Service auth,
    using the same mechanism as
    resources/testing/integration/create-mqtt-service-x509-cert.sh: the CN of
    a self-signed certificate, uploaded as a trusted certificate, IS the MQTT
    clientId (the tenant id goes in the MQTT username field, no password is
    used). Because clientId must equal the cert CN, one cert is provisioned
    per worker connection.
"""

import argparse
import os
import random
import shutil
import string
import subprocess
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass


def get_env(key, default=None):
    return os.environ.get(key, default)


def fail(message):
    raise SystemExit(f"ERROR: {message}")


def _positive_float(value):
    try:
        result = float(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("must be a positive number")
    if not 0 < result < float("inf"):
        raise argparse.ArgumentTypeError("must be a finite number greater than zero")
    return result


def add_common_args(parser: argparse.ArgumentParser, default_total_tps: float):
    parser.add_argument(
        "--total-tps", type=_positive_float,
        default=_positive_float(get_env("TOTAL_TPS", default_total_tps)),
        help=f"Target aggregate publish rate across all workers, in messages/sec "
             f"(default: {default_total_tps}, env: TOTAL_TPS)",
    )
    parser.add_argument(
        "--max-tps-per-client", type=_positive_float,
        default=_positive_float(get_env("MAX_TPS_PER_CLIENT", 90)),
        help="Per-MQTT-connection rate cap; the Cumulocity MQTT Service enforces "
             "a hard limit of ~100 msg/s per client (default: 90, env: MAX_TPS_PER_CLIENT)",
    )
    parser.add_argument(
        "--auth", choices=["cert", "password"],
        default=get_env("MQTT_AUTH", "cert"),
        help="Authentication mode: 'cert' (default) provisions one short-lived "
             "X.509 client certificate per worker connection and uploads it as a "
             "trusted certificate, mirroring "
             "resources/testing/integration/create-mqtt-service-x509-cert.sh "
             "(requires the c8y CLI to be logged in, + openssl); 'password' uses "
             "MQTT_USERNAME/MQTT_PASSWORD or a C8Y JWT (env: MQTT_AUTH)",
    )
    parser.add_argument(
        "--broker", default=get_env("MQTT_BROKER") or get_env("C8Y_DOMAIN"),
        help="MQTT broker host (default: $MQTT_BROKER or $C8Y_DOMAIN)",
    )
    parser.add_argument(
        "--port", type=int, default=int(get_env("MQTT_PORT", 9883)),
        help="MQTT broker port (default: 9883, env: MQTT_PORT)",
    )
    parser.add_argument(
        "--cert-days", type=int, default=int(get_env("DM_CERT_DAYS", 2)),
        help="Validity, in days, of generated client certificates (default: 2, env: DM_CERT_DAYS)",
    )
    return parser


@dataclass
class ClientCert:
    client_id: str
    cert_path: str
    key_path: str


def _run(cmd, **kwargs):
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def _random_suffix(n=8):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def provision_client_certs(count, cert_dir, days=2, prefix="dmloadtest"):
    """Generate `count` self-signed client certs and upload each as a trusted
    certificate, so the Cumulocity MQTT Service accepts it for X.509 auth.

    clientId MUST equal the cert CN (the MQTT Service derives the subscriber
    name from it), and the CN must be alphanumeric — a non-alphanumeric name
    is rejected with HTTP 422. Each cert is self-signed and uploaded as its
    own trusted certificate, so no shared CA is needed.
    """
    if not shutil.which("openssl"):
        fail("openssl is required to provision MQTT client certificates.")
    if not shutil.which("c8y"):
        fail("c8y CLI is required to upload trusted certificates (run 'c8y login' first).")

    os.makedirs(cert_dir, exist_ok=True)
    os.chmod(cert_dir, 0o700)

    def _provision_one(i):
        client_id = f"{prefix}{i}{_random_suffix()}"
        key_path = os.path.join(cert_dir, f"{client_id}.key")
        cert_path = os.path.join(cert_dir, f"{client_id}.pem")
        r = _run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", key_path, "-out", cert_path,
            "-days", str(days), "-subj", f"/CN={client_id}",
        ])
        if r.returncode != 0:
            fail(f"Failed to generate certificate for {client_id}: {r.stderr.strip()}")
        os.chmod(key_path, 0o600)
        r = _run([
            "c8y", "devicemanagement", "certificates", "create",
            "--name", client_id, "--file", cert_path,
            "--autoRegistrationEnabled", "--force", "--output", "json",
        ], stdin=subprocess.DEVNULL)
        if r.returncode != 0:
            fail(
                f"Failed to upload trusted certificate '{client_id}': {r.stderr.strip()}\n"
                "Does the c8y session have the 'Mqtt service' permission?"
            )
        return ClientCert(client_id, cert_path, key_path)

    with ThreadPoolExecutor(max_workers=min(count, 10)) as pool:
        return list(pool.map(_provision_one, range(count)))


def cleanup_client_certs(certs, cert_dir):
    """Best-effort: delete the uploaded trusted certificates and local files."""
    for c in certs:
        _run(["c8y", "devicemanagement", "certificates", "delete", "--id", c.client_id, "--force"],
             stdin=subprocess.DEVNULL)
    shutil.rmtree(cert_dir, ignore_errors=True)


def resolve_tenant():
    tenant = get_env("C8Y_TENANT", "")
    if not tenant:
        fail("Cert auth requires C8Y_TENANT (used as the MQTT username field). Set it or pass --auth password.")
    return tenant
