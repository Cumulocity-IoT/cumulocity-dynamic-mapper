/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.core.PlatformProperties;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Answers one question from inside the running service: <b>can the owner tenant's service user read
 * this microservice's own logs?</b> That decides whether a log collector can live in the service
 * (see docs/feature/service-logs.md, "Credentials of the owner tenant").
 *
 * <p>It makes the same calls the Administration application makes, as the service user the
 * platform gave this service in the owner tenant, and reports what happened: the HTTP status of
 * every step, the roles the service user actually holds, and a verdict. It never returns log
 * content or credentials.
 */
@Slf4j
@Service
public class ServiceLogsProbe {

    public static final String LOGS_MEDIA_TYPE = "application/vnd.com.nsn.cumulocity.applicationLogs+json";

    /** The only HTTP access the probe needs; replaced in tests. */
    @FunctionalInterface
    public interface Http {
        Response get(String path, String acceptHeader) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {
    }

    public enum Verdict {
        /** The service user read the logs: a collector can run inside the service. */
        CAN_READ_LOGS,
        /**
         * The service user is refused but the owner tenant's <em>bootstrap</em> user, which the platform already
         * hands the service, can read the logs: no manifest change is needed.
         */
        CAN_READ_LOGS_AS_BOOTSTRAP_USER,
        /**
         * Both users are refused on the platform's internal address, but the bootstrap user may read the logs through the
         * tenant's public host. The collector then has to call the public host.
         */
        CAN_READ_LOGS_AS_BOOTSTRAP_USER_VIA_PUBLIC_HOST,
        /**
         * Plain Basic authentication is refused everywhere, but the bootstrap user may read the logs with an OAuth token
         * obtained from the tenant's public host. The collector then has to log in and send the token.
         */
        CAN_READ_LOGS_AS_BOOTSTRAP_USER_WITH_TOKEN,
        /** The platform refused the service user: see the roles and add the missing one to requiredRoles. */
        FORBIDDEN,
        /** The owner tenant has not subscribed to this service, so it has no service user at all. */
        OWNER_NOT_SUBSCRIBED,
        /** A step before the logs call failed; see the steps. */
        INCONCLUSIVE
    }

    public record Step(String name, int httpStatus, String detail) {
    }

    public record Result(Verdict verdict, String ownerTenant, String instance, List<String> effectiveRoles,
            List<String> applicationManagementRoles, List<Step> steps, String advice) {
    }

    private final MicroserviceSubscriptionsService subscriptionsService;
    private final PlatformProperties platformProperties;
    private final ObjectMapper objectMapper;
    private final String ownerTenant;
    private final String applicationName;

    public ServiceLogsProbe(MicroserviceSubscriptionsService subscriptionsService,
            PlatformProperties platformProperties, ObjectMapper objectMapper,
            @Value("${C8Y_BOOTSTRAP_TENANT:}") String ownerTenant,
            @Value("${application.name:dynamic-mapper-service}") String applicationName) {
        this.subscriptionsService = subscriptionsService;
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.ownerTenant = ownerTenant;
        this.applicationName = applicationName;
    }

    public String getOwnerTenant() {
        return ownerTenant;
    }

    /** Runs the probe as the owner tenant's service user. */
    public Result probe(String ownInstanceName, String applicationIdOverride) {
        if (ownerTenant == null || ownerTenant.isBlank()) {
            return new Result(Verdict.INCONCLUSIVE, null, ownInstanceName, List.of(), List.of(),
                    List.of(new Step("owner tenant", 0, "C8Y_BOOTSTRAP_TENANT is not set")),
                    "The platform did not provide the owner tenant, so the probe cannot choose a service user.");
        }
        Optional<MicroserviceCredentials> credentials = subscriptionsService.getCredentials(ownerTenant);
        List<String> subscribed = subscriptionsService.getAll().stream()
                .map(MicroserviceCredentials::getTenant).sorted().toList();
        if (credentials.isEmpty()) {
            return probe(ownerTenant, ownInstanceName, null, null,
                    List.of(new Step("owner credentials", 0, "none; the SDK knows these subscribed tenants: " + subscribed)));
        }
        MicroserviceCredentials c = credentials.get();
        // The user NAME tells a service user ("service_...") from a bootstrap user ("servicebootstrap_...").
        List<Step> pre = List.of(new Step("owner credentials", 0, "authenticating as '" + c.getUsername()
                + "' in tenant " + c.getTenant() + "; the SDK knows these subscribed tenants: " + subscribed));
        String baseUrl = platformProperties.getUrl().get();
        Http http = basicAuth(baseUrl, c.getTenant(), c.getUsername(), c.getPassword());
        // The bootstrap user lives in the owner tenant and is already injected into the service by the SDK.
        Credentials bootstrap = platformProperties.getMicroserviceBoostrapUser();
        Http bootstrapHttp = bootstrap == null || bootstrap.getUsername() == null ? null
                : basicAuth(baseUrl, bootstrap.getTenant(), bootstrap.getUsername(), bootstrap.getPassword());
        List<Step> allPre = new ArrayList<>(pre);
        allPre.add(new Step("bootstrap credentials", 0, bootstrap == null ? "none provided"
                : "'" + bootstrap.getUsername() + "' in tenant " + bootstrap.getTenant() + " via " + baseUrl));
        List<java.util.function.Supplier<Http>> routes = bootstrap == null || bootstrap.getUsername() == null ? List.of()
                : List.of(() -> publicHttp(http, bootstrap, false), () -> publicHttp(http, bootstrap, true));
        return probe(ownerTenant, ownInstanceName, applicationIdOverride, http, allPre, bootstrapHttp, routes);
    }

    /**
     * Basic-auth access to the tenant's public host, as the bootstrap user. The host name is the owner tenant's own
     * {@code domainName}, asked for with the service user over the internal address.
     */
    Http publicHttp(Http serviceUserHttp, Credentials bootstrap, boolean withToken) {
        try {
            Response tenant = serviceUserHttp.get("/tenant/currentTenant", "application/json");
            String domain = tenant.status() == 200 ? objectMapper.readTree(tenant.body()).path("domainName").asText(null) : null;
            if (domain == null || domain.isBlank()) {
                throw new IllegalStateException("the owner tenant's domain name is unknown (HTTP " + tenant.status() + ")");
            }
            return withToken
                    ? oauthBearer("https://" + domain, bootstrap.getTenant(), bootstrap.getUsername(), bootstrap.getPassword())
                    : basicAuth("https://" + domain, bootstrap.getTenant(), bootstrap.getUsername(), bootstrap.getPassword());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Token access: logs in once at {@code /tenant/oauth} (what the platform's own applications and the {@code c8y} CLI do)
     * and sends the returned token as a bearer token. Some endpoints treat a token and plain Basic authentication
     * differently. Shared with the log collector.
     */
    public static Http oauthBearer(String baseUrl, String tenant, String user, String password) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        java.util.concurrent.atomic.AtomicReference<String> token = new java.util.concurrent.atomic.AtomicReference<>();
        return (path, accept) -> {
            String bearer = token.get();
            if (bearer == null) {
                HttpResponse<String> login = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/tenant/oauth?tenant_id="
                                + URLEncoder.encode(tenant, StandardCharsets.UTF_8)))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString("grant_type=PASSWORD&username="
                                        + URLEncoder.encode(user, StandardCharsets.UTF_8) + "&password="
                                        + URLEncoder.encode(password, StandardCharsets.UTF_8)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                bearer = login.headers().allValues("set-cookie").stream()
                        .filter(c -> c.startsWith("authorization=")).map(c -> c.substring("authorization=".length()))
                        .map(c -> c.contains(";") ? c.substring(0, c.indexOf(';')) : c).findFirst().orElse(null);
                if (bearer == null) {
                    throw new IOException("The OAuth login returned HTTP " + login.statusCode() + " and no token");
                }
                token.set(bearer);
            }
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + bearer).header("Accept", accept).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    /** Basic-auth access to the platform; shared with the log collector. */
    public static Http basicAuth(String baseUrl, String tenant, String user, String password) {
        String basic = Base64.getEncoder().encodeToString(
                (tenant + "/" + user + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (path, accept) -> {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Basic " + basic)
                            .header("Accept", accept)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    Result probe(String owner, String ownInstanceName, String applicationIdOverride, Http http, List<Step> preSteps) {
        return probe(owner, ownInstanceName, applicationIdOverride, http, preSteps, null, List.of());
    }

    Result probe(String owner, String ownInstanceName, String applicationIdOverride, Http http, List<Step> preSteps,
            Http bootstrapHttp) {
        return probe(owner, ownInstanceName, applicationIdOverride, http, preSteps, bootstrapHttp, List.of());
    }

    Result probe(String owner, String ownInstanceName, String applicationIdOverride, Http http) {
        return probe(owner, ownInstanceName, applicationIdOverride, http, List.of());
    }

    /** The decision logic, separate from where the HTTP responses come from. {@code http == null}: not subscribed. */
    Result probe(String owner, String ownInstanceName, String applicationIdOverride, Http http, List<Step> preSteps,
            Http bootstrapHttp, List<java.util.function.Supplier<Http>> fallbackRoutes) {
        List<Step> steps = new ArrayList<>(preSteps);
        if (http == null) {
            return new Result(Verdict.OWNER_NOT_SUBSCRIBED, owner, ownInstanceName, List.of(), List.of(), steps,
                    "Subscribe the owner tenant (" + owner + ") to this microservice, then run the probe again. "
                            + "Without that subscription the owner tenant has no service user.");
        }
        try {
            // 1. Who are we, and what can we do?
            Response me = http.get("/user/currentUser", "application/json");
            steps.add(new Step("service user: currentUser", me.status(), null));
            List<String> roles = new ArrayList<>();
            if (me.status() == 200) {
                for (JsonNode role : objectMapper.readTree(me.body()).path("effectiveRoles")) {
                    roles.add(role.path("id").asText(role.asText()));
                }
            }
            List<String> appMgmt = roles.stream().filter(r -> r.startsWith("ROLE_APPLICATION_MANAGEMENT")).toList();

            // 2+3. Which application is this, and which instances run? The application's managed object
            // answers both. /application/currentApplication is NOT used: it refuses service users (403).
            Response mo = applicationIdOverride != null && !applicationIdOverride.isBlank()
                    ? http.get("/inventory/managedObjects?pageSize=20&type="
                            + URLEncoder.encode("c8y_Application_" + applicationIdOverride.trim(), StandardCharsets.UTF_8),
                            "application/json")
                    : http.get("/inventory/managedObjects?pageSize=20&query=" + URLEncoder.encode(
                            "$filter=(name eq '" + applicationName + "') and (type eq 'c8y_Application_*')",
                            StandardCharsets.UTF_8).replace("+", "%20"), "application/json");
            // Several managed objects can match (stale ones from earlier deployments keep their record):
            // take the one whose instances include THIS pod, so the application id is the live one.
            JsonNode candidates = mo.status() == 200 ? objectMapper.readTree(mo.body()).path("managedObjects") : null;
            JsonNode appMo = null;
            List<String> seen = new ArrayList<>();
            if (candidates != null) {
                for (JsonNode candidate : candidates) {
                    List<String> names = new ArrayList<>();
                    candidate.path("c8y_Status").path("instances").fieldNames().forEachRemaining(names::add);
                    // ids only: instance names carry other tenants' ids and the list can be long
                    seen.add(candidate.path("applicationId").asText("?"));
                    if (appMo == null && ownInstanceName != null && names.contains(ownInstanceName)) {
                        appMo = candidate;
                    }
                }
                if (appMo == null && candidates.size() > 0) {
                    appMo = candidates.get(0);
                }
            }
            String appId = appMo == null ? null : appMo.path("applicationId").asText(applicationIdOverride);
            steps.add(new Step("application managed object", mo.status(),
                    appId == null ? "not found" : "using application id " + appId + " out of " + seen.size() + " candidate(s) " + seen));
            if (appId == null) {
                return inconclusive(owner, ownInstanceName, roles, appMgmt, steps,
                        "Could not find the application's managed object as the service user. Run the operation again "
                                + "with {\"applicationId\": \"<id>\"} (the id in the Administration URL of the microservice).");
            }
            List<String> instances = new ArrayList<>();
            appMo.path("c8y_Status").path("instances").fieldNames().forEachRemaining(instances::add);
            String instance = ownInstanceName != null && instances.contains(ownInstanceName) ? ownInstanceName
                    : instances.isEmpty() ? null : instances.get(0);
            steps.add(new Step("instances", mo.status(), instances.size() + " found; this instance "
                    + (instances.contains(ownInstanceName) ? "is" : "is NOT") + " among them (" + ownInstanceName + ")"));
            if (instance == null) {
                return inconclusive(owner, ownInstanceName, roles, appMgmt, steps, "No running instance was found.");
            }

            // 4. The actual question.
            Response logs = http.get("/application/applications/" + appId + "/logs/"
                    + URLEncoder.encode(instance, StandardCharsets.UTF_8), LOGS_MEDIA_TYPE);
            String detail = null;
            if (logs.status() == 200) {
                JsonNode body = objectMapper.readTree(logs.body());
                detail = "read " + body.path("logs").asText("").lines().count() + " line(s), "
                        + body.path("dateFrom").asText() + " .. " + body.path("dateTo").asText();
            } else if (logs.body() != null) {
                detail = objectMapper.readTree(logs.body()).path("message").asText(null);
            }
            steps.add(new Step("logs of " + instance, logs.status(), detail));

            if (logs.status() == 200) {
                return new Result(Verdict.CAN_READ_LOGS, owner, instance, roles, appMgmt, steps,
                        "The owner tenant's service user can read the logs. A collector can run inside the service"
                                + (appMgmt.isEmpty() ? " (no ROLE_APPLICATION_MANAGEMENT_* needed)." : "."));
            }
            if (logs.status() == 403 && bootstrapHttp != null) {
                Response asBootstrap = bootstrapHttp.get("/application/applications/" + appId + "/logs/"
                        + URLEncoder.encode(instance, StandardCharsets.UTF_8), LOGS_MEDIA_TYPE);
                String why = null;
                if (asBootstrap.status() != 200 && asBootstrap.body() != null) {
                    try {
                        why = objectMapper.readTree(asBootstrap.body()).path("message").asText(null);
                    } catch (Exception ignored) {
                        // not JSON
                    }
                }
                steps.add(new Step("logs of " + instance + " as the bootstrap user", asBootstrap.status(), why));
                if (asBootstrap.status() == 200) {
                    return new Result(Verdict.CAN_READ_LOGS_AS_BOOTSTRAP_USER, owner, instance, roles, appMgmt, steps,
                            "The service user is refused, but the owner tenant's bootstrap user can read the logs. A collector "
                                    + "inside the service can use it (PlatformProperties.getMicroserviceBoostrapUser()); no "
                                    + "requiredRoles change is needed.");
                }
                if (asBootstrap.status() == 403) {
                    String[] labels = { "via the public host", "via the public host with an OAuth token" };
                    Verdict[] verdicts = { Verdict.CAN_READ_LOGS_AS_BOOTSTRAP_USER_VIA_PUBLIC_HOST,
                            Verdict.CAN_READ_LOGS_AS_BOOTSTRAP_USER_WITH_TOKEN };
                    for (int i = 0; i < fallbackRoutes.size() && i < labels.length; i++) {
                        String routeWhy = null;
                        int routeStatus = 0;
                        try {
                            Response viaRoute = fallbackRoutes.get(i).get().get("/application/applications/" + appId + "/logs/"
                                    + URLEncoder.encode(instance, StandardCharsets.UTF_8), LOGS_MEDIA_TYPE);
                            routeStatus = viaRoute.status();
                            if (routeStatus != 200 && viaRoute.body() != null) {
                                try {
                                    routeWhy = objectMapper.readTree(viaRoute.body()).path("message").asText(null);
                                } catch (Exception ignored) {
                                    // not JSON
                                }
                            }
                        } catch (Exception e) {
                            routeWhy = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                        steps.add(new Step("logs of " + instance + " as the bootstrap user " + labels[i], routeStatus, routeWhy));
                        if (routeStatus == 200) {
                            return new Result(verdicts[i], owner, instance, roles, appMgmt, steps,
                                    "The bootstrap user can read the logs " + labels[i] + ". The collector uses that route; no "
                                            + "requiredRoles change is needed.");
                        }
                    }
                }
            }
            if (logs.status() == 403) {
                return new Result(Verdict.FORBIDDEN, owner, instance, roles, appMgmt, steps,
                        "The platform refused the service user" + (bootstrapHttp != null ? " and the bootstrap user" : "") + ". The service user holds " + roles.size() + " role(s), "
                                + (appMgmt.isEmpty() ? "none of them ROLE_APPLICATION_MANAGEMENT_*" : appMgmt)
                                + ". Add ROLE_APPLICATION_MANAGEMENT_READ to requiredRoles in the manifest, redeploy, "
                                + "and run the probe again. If it is still refused, the collector must run outside the service.");
            }
            if (logs.status() == 404) {
                return inconclusive(owner, instance, roles, appMgmt, steps,
                        "The platform does not know application " + appId + " (404). The managed object is probably a stale "
                                + "record of an earlier deployment" + (instances.contains(ownInstanceName) ? "" : " — this pod is not among its instances")
                                + ". Run the operation again with {\"applicationId\": \"<id>\"}, taking the id from the "
                                + "Administration URL of the microservice.");
            }
            if (logs.status() == 401) {
                return inconclusive(owner, instance, roles, appMgmt, steps,
                        "The platform did not accept the service user's credentials (401), which is not a missing "
                                + "role. The probe authenticates with Basic auth; if the platform only accepts tokens for "
                                + "service users, this probe cannot answer the question.");
            }
            return inconclusive(owner, instance, roles, appMgmt, steps, "Unexpected status " + logs.status() + " from the logs call.");
        } catch (Exception e) {
            log.warn("Service logs probe failed: {}", e.getMessage());
            steps.add(new Step("error", 0, e.getClass().getSimpleName() + ": " + e.getMessage()));
            return inconclusive(owner, ownInstanceName, List.of(), List.of(), steps, "The probe itself failed.");
        }
    }

    private static Result inconclusive(String owner, String instance, List<String> roles, List<String> appMgmt,
            List<Step> steps, String advice) {
        return new Result(Verdict.INCONCLUSIVE, owner, instance, roles, appMgmt, steps, advice);
    }
}
