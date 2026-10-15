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

    static final String LOGS_MEDIA_TYPE = "application/vnd.com.nsn.cumulocity.applicationLogs+json";

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

    public ServiceLogsProbe(MicroserviceSubscriptionsService subscriptionsService,
            PlatformProperties platformProperties, ObjectMapper objectMapper,
            @Value("${C8Y_BOOTSTRAP_TENANT:}") String ownerTenant) {
        this.subscriptionsService = subscriptionsService;
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.ownerTenant = ownerTenant;
    }

    public String getOwnerTenant() {
        return ownerTenant;
    }

    /** Runs the probe as the owner tenant's service user. */
    public Result probe(String ownInstanceName) {
        if (ownerTenant == null || ownerTenant.isBlank()) {
            return new Result(Verdict.INCONCLUSIVE, null, ownInstanceName, List.of(), List.of(),
                    List.of(new Step("owner tenant", 0, "C8Y_BOOTSTRAP_TENANT is not set")),
                    "The platform did not provide the owner tenant, so the probe cannot choose a service user.");
        }
        Optional<MicroserviceCredentials> credentials = subscriptionsService.getCredentials(ownerTenant);
        if (credentials.isEmpty()) {
            return probe(ownerTenant, ownInstanceName, null);
        }
        MicroserviceCredentials c = credentials.get();
        String basic = Base64.getEncoder().encodeToString(
                (c.getTenant() + "/" + c.getUsername() + ":" + c.getPassword()).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String baseUrl = platformProperties.getUrl().get();
        Http http = (path, accept) -> {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Basic " + basic)
                            .header("Accept", accept)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
        return probe(ownerTenant, ownInstanceName, http);
    }

    /** The decision logic, separate from where the HTTP responses come from. {@code http == null}: not subscribed. */
    Result probe(String owner, String ownInstanceName, Http http) {
        List<Step> steps = new ArrayList<>();
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

            // 2. Which application is this?
            Response app = http.get("/application/currentApplication", "application/json");
            String appId = app.status() == 200 ? objectMapper.readTree(app.body()).path("id").asText(null) : null;
            steps.add(new Step("application: currentApplication", app.status(), appId == null ? null : "id " + appId));
            if (appId == null) {
                return inconclusive(owner, ownInstanceName, roles, appMgmt, steps,
                        "Could not identify the application as the service user.");
            }

            // 3. Which instances exist, and is this one among them?
            Response mo = http.get("/inventory/managedObjects?type="
                    + URLEncoder.encode("c8y_Application_" + appId, StandardCharsets.UTF_8) + "&pageSize=1",
                    "application/json");
            List<String> instances = new ArrayList<>();
            if (mo.status() == 200) {
                JsonNode first = objectMapper.readTree(mo.body()).path("managedObjects").path(0);
                first.path("c8y_Status").path("instances").fieldNames().forEachRemaining(instances::add);
            }
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
            if (logs.status() == 403) {
                return new Result(Verdict.FORBIDDEN, owner, instance, roles, appMgmt, steps,
                        "The platform refused the service user. It holds " + roles.size() + " role(s), "
                                + (appMgmt.isEmpty() ? "none of them ROLE_APPLICATION_MANAGEMENT_*" : appMgmt)
                                + ". Add ROLE_APPLICATION_MANAGEMENT_READ to requiredRoles in the manifest, redeploy, "
                                + "and run the probe again. If it is still refused, the collector must run outside the service.");
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
