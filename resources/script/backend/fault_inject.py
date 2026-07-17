from mitmproxy import command, ctx, http

# Set this to your own tenant host before running — e.g. "myTenant.eu-latest.cumulocity.com".
# Deliberately left as a placeholder: this file is checked into the repo, unlike the gitignored
# application-dev.properties, so no real tenant hostname belongs here.
REAL_HOST = "<your-tenant>.cumulocity.com"
FAULT_PATH_PREFIX = "/inventory/managedObjects"


class FaultInjector:
    def __init__(self):
        self.enabled = False

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

    def request(self, flow: http.HTTPFlow):
        # Reverse-proxy mode forwards whatever Host header the client sent
        # (localhost:8888); Cumulocity routes tenants by subdomain, so the
        # Host header must be rewritten back to the real tenant host or
        # every request (including auth) will fail for unrelated reasons.
        flow.request.host_header = REAL_HOST

    def response(self, flow: http.HTTPFlow):
        if self.enabled and flow.request.path.startswith(FAULT_PATH_PREFIX):
            flow.response = http.Response.make(
                502, b"Bad Gateway", {"Content-Type": "text/plain"}
            )


addons = [FaultInjector()]
