/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package dynamic.mapper.util;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.log.LogMessage;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Map;

/**
 * Probes the configured OTLP collector (Grafana Alloy) once at startup, before the OpenTelemetry
 * SDK autoconfigures, and disables the SDK ({@code otel.sdk.disabled=true}) when the collector is
 * unreachable. Deployers of this microservice don't control the environment variables it starts
 * with, so the check can't rely on someone setting {@code OTEL_SDK_DISABLED} externally — it has
 * to happen in code. Without this, an unreachable collector still works fine (export runs on
 * background threads and never blocks the app), but the OTel SDK retries forever and logs a
 * connection-failure stack trace on every attempt; see docs/installation.md "Monitoring
 * (OpenTelemetry)".
 *
 * <p>The packaged {@code application*.properties} always ship {@code otel.sdk.disabled=false}, so
 * that value alone isn't treated as an explicit choice — only an actual {@code OTEL_SDK_DISABLED}
 * environment variable or {@code -Dotel.sdk.disabled} system property skips the probe, letting an
 * operator force the SDK on or off regardless of reachability.
 *
 * <p>This is a one-shot startup check: if the collector comes back later, export stays disabled
 * until the process restarts.
 */
public class OtlpCollectorReachabilityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DISABLED_PROPERTY = "otel.sdk.disabled";
    private static final String ENDPOINT_PROPERTY = "otel.exporter.otlp.endpoint";
    private static final String DEFAULT_ENDPOINT = "http://localhost:4318";
    private static final int CONNECT_TIMEOUT_MS = 1500;

    private final Log log;

    public OtlpCollectorReachabilityEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isExplicitlySet(environment) || environment.getProperty(DISABLED_PROPERTY, Boolean.class, false)) {
            return;
        }

        String endpoint = environment.getProperty(ENDPOINT_PROPERTY, DEFAULT_ENDPOINT);
        if (isReachable(endpoint)) {
            return;
        }

        log.warn(LogMessage.format(
                "OTLP collector at %s is not reachable; disabling OpenTelemetry export for this run "
                        + "to avoid log spam (restart once the collector is reachable, or set "
                        + "OTEL_SDK_DISABLED=false explicitly to force it on)",
                endpoint));
        environment.getPropertySources()
                .addFirst(new MapPropertySource("otlpCollectorReachability", Map.of(DISABLED_PROPERTY, "true")));
    }

    /** True only if an operator explicitly set the property via env var or system property. */
    private boolean isExplicitlySet(ConfigurableEnvironment environment) {
        PropertySource<?> systemProperties = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        PropertySource<?> systemEnvironment = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return (systemProperties != null && systemProperties.containsProperty(DISABLED_PROPERTY))
                || (systemEnvironment != null && systemEnvironment.containsProperty(DISABLED_PROPERTY));
    }

    private boolean isReachable(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
