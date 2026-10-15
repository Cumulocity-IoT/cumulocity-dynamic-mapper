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

package dynamic.mapper.logs;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.core.PlatformProperties;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.ServiceLogsProbe;
import dynamic.mapper.core.ServiceLogsProbe.Http;
import dynamic.mapper.core.ServiceLogsProbe.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads this instance's own log from the platform.
 *
 * <p>The log is read with the owner tenant's <b>bootstrap user</b>: measured on a deployed service, the
 * service user is refused (403) while the bootstrap user may read it, so no {@code requiredRoles} change is
 * needed (docs/feature/service-logs.md). The bootstrap credentials are used for this one read call only and
 * are never logged. The application and instance are found with the owner's service user, because the
 * bootstrap user is not meant for inventory access.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "service.logs.collector", name = "enabled", havingValue = "true")
public class PlatformServiceLogSource implements ServiceLogSource {

    private static final String LOGS_MEDIA_TYPE = ServiceLogsProbe.LOGS_MEDIA_TYPE;

    private final MicroserviceSubscriptionsService subscriptionsService;
    private final PlatformProperties platformProperties;
    private final ObjectMapper objectMapper;
    private final String ownerTenant;
    private final String applicationName;
    private final String instance;

    private volatile String applicationId;
    private volatile Http bootstrapHttp;
    private volatile Http bootstrapPublicHttp;
    private volatile boolean usePublicHost;
    private volatile String bootstrapDescription = "unknown user";

    public PlatformServiceLogSource(MicroserviceSubscriptionsService subscriptionsService,
            PlatformProperties platformProperties, ObjectMapper objectMapper,
            @Value("${C8Y_BOOTSTRAP_TENANT:}") String ownerTenant,
            @Value("${application.name:dynamic-mapper-service}") String applicationName) {
        this.subscriptionsService = subscriptionsService;
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.ownerTenant = ownerTenant;
        this.applicationName = applicationName;
        this.instance = System.getenv("HOSTNAME");
    }

    @Override
    public String instance() {
        return instance;
    }

    @Override
    public Window fetch(Instant from) throws IOException, InterruptedException {
        if (instance == null || instance.isBlank()) {
            throw new IOException("HOSTNAME is not set, so this instance cannot find its own log");
        }
        String appId = applicationId != null ? applicationId : (applicationId = locateApplication());
        String path = "/application/applications/" + appId + "/logs/" + URLEncoder.encode(instance, StandardCharsets.UTF_8)
                + "?dateFrom=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8);
        Response response = (usePublicHost ? bootstrapPublic() : bootstrap()).get(path, LOGS_MEDIA_TYPE);
        if (response.status() == 403 && !usePublicHost) {
            // Measured: the internal address refuses this endpoint even for the bootstrap user, the public host does not.
            Response viaPublic = bootstrapPublic().get(path, LOGS_MEDIA_TYPE);
            if (viaPublic.status() == 200) {
                usePublicHost = true;
                log.info("Service log collector: the internal platform address refuses the log, reading it through the "
                        + "tenant's public host from now on");
                response = viaPublic;
            }
        }
        if (response.status() == 404) {
            applicationId = null; // stale id: look it up again next time
        }
        if (response.status() != 200) {
            throw new IOException("Reading the log returned HTTP " + response.status() + " for " + bootstrapDescription
                    + platformMessage(response));
        }
        JsonNode body = objectMapper.readTree(response.body());
        return new Window(instant(body.path("dateFrom")), instant(body.path("dateTo")),
                body.path("logs").asText(""), body.path("truncated").asBoolean(false));
    }

    /** The platform's own explanation, if the body has one: ": Access Denied". Never the credentials. */
    private String platformMessage(Response response) {
        try {
            String message = objectMapper.readTree(response.body()).path("message").asText(null);
            return message == null || message.isBlank() ? "" : ": " + message;
        } catch (Exception e) {
            return "";
        }
    }

    private static Instant instant(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : Instant.parse(node.asText());
    }

    private Http bootstrap() throws IOException {
        Http http = bootstrapHttp;
        if (http == null) {
            Credentials c = platformProperties.getMicroserviceBoostrapUser();
            if (c == null || c.getUsername() == null) {
                throw new IOException("The platform did not provide bootstrap credentials");
            }
            bootstrapDescription = "'" + c.getUsername() + "' in tenant " + c.getTenant() + " via "
                    + platformProperties.getUrl().get();
            http = bootstrapHttp = ServiceLogsProbe.basicAuth(platformProperties.getUrl().get(), c.getTenant(),
                    c.getUsername(), c.getPassword());
        }
        return http;
    }

    /** The bootstrap user's access through the tenant's public host, whose name the owner tenant itself reports. */
    private Http bootstrapPublic() throws IOException, InterruptedException {
        Http http = bootstrapPublicHttp;
        if (http == null) {
            Credentials c = platformProperties.getMicroserviceBoostrapUser();
            MicroserviceCredentials serviceUser = subscriptionsService.getCredentials(ownerTenant)
                    .orElseThrow(() -> new IOException("The owner tenant " + ownerTenant + " is not subscribed to this service"));
            Http internal = ServiceLogsProbe.basicAuth(platformProperties.getUrl().get(), serviceUser.getTenant(),
                    serviceUser.getUsername(), serviceUser.getPassword());
            Response tenant = internal.get("/tenant/currentTenant", "application/json");
            String domain = tenant.status() == 200
                    ? objectMapper.readTree(tenant.body()).path("domainName").asText(null) : null;
            if (domain == null || domain.isBlank()) {
                throw new IOException("The owner tenant's domain name is unknown (HTTP " + tenant.status() + ")");
            }
            bootstrapDescription = "'" + c.getUsername() + "' in tenant " + c.getTenant() + " via https://" + domain;
            http = bootstrapPublicHttp = ServiceLogsProbe.basicAuth("https://" + domain, c.getTenant(), c.getUsername(),
                    c.getPassword());
        }
        return http;
    }

    /** The application record that lists THIS pod (stale records of earlier deployments also match by name). */
    private String locateApplication() throws IOException, InterruptedException {
        Optional<MicroserviceCredentials> serviceUser = subscriptionsService.getCredentials(ownerTenant);
        if (serviceUser.isEmpty()) {
            throw new IOException("The owner tenant " + ownerTenant + " is not subscribed to this service");
        }
        MicroserviceCredentials c = serviceUser.get();
        Http http = ServiceLogsProbe.basicAuth(platformProperties.getUrl().get(), c.getTenant(), c.getUsername(),
                c.getPassword());
        Response response = http.get("/inventory/managedObjects?pageSize=20&query=" + URLEncoder.encode(
                "$filter=(name eq '" + applicationName + "') and (type eq 'c8y_Application_*')",
                StandardCharsets.UTF_8).replace("+", "%20"), "application/json");
        if (response.status() != 200) {
            throw new IOException("Looking up the application returned HTTP " + response.status());
        }
        List<String> seen = new ArrayList<>();
        for (JsonNode candidate : objectMapper.readTree(response.body()).path("managedObjects")) {
            candidate.path("c8y_Status").path("instances").fieldNames().forEachRemaining(name -> {
                if (name.equals(instance)) {
                    seen.add(candidate.path("applicationId").asText());
                }
            });
        }
        if (seen.isEmpty()) {
            throw new IOException("No application record lists this instance (" + instance + ")");
        }
        return seen.get(0);
    }
}
