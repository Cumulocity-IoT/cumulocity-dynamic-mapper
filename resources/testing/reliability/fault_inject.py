import os

from mitmproxy import command, ctx, http

# Set via the REAL_HOST env var before running mitmproxy, e.g.:
#   REAL_HOST=myTenant.eu-latest.cumulocity.com mitmproxy --mode reverse:https://myTenant.eu-latest.cumulocity.com ...
# Deliberately left as a placeholder here: this file is checked into the repo, unlike the gitignored
# application-dev.properties, so no real tenant hostname belongs in the committed default.
REAL_HOST = os.environ.get("REAL_HOST", "<your-tenant>.cumulocity.com")
# The default fault: the inventory (mapping reload after an MQTT reconnect).
FAULT_PATH_PREFIX = "/inventory/managedObjects"
# The service log's calls (dynamic.mapper.logs.store): events written by EventServiceLogSink, and the external-id
# lookup/registration of ServiceLogSourceLocator. Switched on separately, because faulting /event/events also hits the
# mapper's own events.
SERVICE_LOG_PATH_PREFIXES = ("/event/events", "/identity/externalIds")


class FaultInjector:
    def __init__(self):
        self.enabled = False
        self.service_log = False

    # Activate from the mitmproxy TUI: press ":" to open the command bar, type
    # "fault.toggle" (or "fault.on" / "fault.off" for an explicit state) and hit Enter.
    # The current state is echoed to the event log (the panel below the flow list).
    @command.command("fault.toggle")
    def toggle(self) -> None:
        self.enabled = not self.enabled
        ctx.log.alert(f"fault injection {'ENABLED' if self.enabled else 'DISABLED'}")

    @command.command("fault.on")
    def on(self) -> None:
        self.enabled = True
        ctx.log.alert("fault injection ENABLED")

    @command.command("fault.off")
    def off(self) -> None:
        self.enabled = False
        ctx.log.alert("fault injection DISABLED")

    # "fault.logs.on" / "fault.logs.off": fail the service log's event and identity calls. Independent of "fault.on";
    # use both to also fail the inventory call that creates the log's source object.
    @command.command("fault.logs.on")
    def logs_on(self) -> None:
        self.service_log = True
        ctx.log.alert("service log fault injection ENABLED")

    @command.command("fault.logs.off")
    def logs_off(self) -> None:
        self.service_log = False
        ctx.log.alert("service log fault injection DISABLED")

    def request(self, flow: http.HTTPFlow):
        # Reverse-proxy mode forwards whatever Host header the client sent
        # (localhost:8888); Cumulocity routes tenants by subdomain, so the
        # Host header must be rewritten back to the real tenant host or
        # every request (including auth) will fail for unrelated reasons.
        flow.request.host_header = REAL_HOST

    def response(self, flow: http.HTTPFlow):
        path = flow.request.path
        faulted = (self.enabled and path.startswith(FAULT_PATH_PREFIX)) or (
            self.service_log and path.startswith(SERVICE_LOG_PATH_PREFIXES)
        )
        if faulted:
            # Applied after the platform handled the request, as in a real gateway failure: a POST can have taken
            # effect although the client sees a 502.
            flow.response = http.Response.make(
                502, b"Bad Gateway", {"Content-Type": "text/plain"}
            )


addons = [FaultInjector()]
