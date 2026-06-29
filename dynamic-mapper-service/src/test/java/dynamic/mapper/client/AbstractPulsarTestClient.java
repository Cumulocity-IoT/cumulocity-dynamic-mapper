/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.client;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import java.util.concurrent.TimeUnit;

/**
 * Shared infrastructure for Pulsar-based test clients.
 *
 * <p>Provides environment-variable configuration, client initialisation,
 * authentication setup, and resource cleanup so that concrete subclasses
 * only need to implement their specific produce/consume logic.</p>
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   PULSAR_BROKER_HOST  (default "pulsar://localhost:6650")
 *   BROKER_USERNAME     (optional — used by "basic" auth)
 *   BROKER_PASSWORD     (optional — used by "basic" auth)
 *   AUTH_NAME           (default "none"; values: none | token | oauth2 | tls | basic)
 *   AUTH_PARAMS         (required when AUTH_NAME != "none")
 *   SUBSCRIPTION_NAME   (default varies per subclass)
 * </pre>
 */
@Slf4j
abstract class AbstractPulsarTestClient {

    // ── Shared environment variables ───────────────────────────────────────
    static final String PULSAR_BROKER_HOST = System.getenv().getOrDefault("PULSAR_BROKER_HOST", "pulsar://localhost:6650");
    static final String BROKER_USERNAME    = System.getenv("BROKER_USERNAME");
    static final String BROKER_PASSWORD    = System.getenv("BROKER_PASSWORD");
    static final String AUTH_NAME          = System.getenv().getOrDefault("AUTH_NAME", "none");
    static final String AUTH_PARAMS        = System.getenv("AUTH_PARAMS");

    // ── Shared client state ────────────────────────────────────────────────
    PulsarClient        client;
    Producer<byte[]>    producer;
    Consumer<byte[]>    consumer;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Creates and connects the Pulsar client, applying authentication when
     * {@code AUTH_NAME} is not {@code "none"}.
     */
    void initialize() throws PulsarClientException {
        log.info("Initialising Pulsar client — broker: {}", PULSAR_BROKER_HOST);

        var builder = PulsarClient.builder()
                .serviceUrl(PULSAR_BROKER_HOST)
                .connectionTimeout(30, TimeUnit.SECONDS)
                .operationTimeout(30, TimeUnit.SECONDS);

        if (!"none".equalsIgnoreCase(AUTH_NAME) && AUTH_PARAMS != null && !AUTH_PARAMS.isEmpty()) {
            configureAuthentication(builder);
        }

        client = builder.build();
        log.info("Pulsar client initialised");
    }

    /** Closes producer, consumer, and client; logs each step. */
    void cleanup() {
        log.info("Cleaning up Pulsar resources...");
        closeQuietly("consumer", () -> { if (consumer != null) consumer.close(); });
        closeQuietly("producer", () -> { if (producer != null) producer.close(); });
        closeQuietly("client",   () -> { if (client   != null) client.close();   });
        log.info("Pulsar cleanup complete");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void configureAuthentication(org.apache.pulsar.client.api.ClientBuilder builder) {
        log.info("Configuring Pulsar authentication — method: {}", AUTH_NAME);
        try {
            switch (AUTH_NAME.toLowerCase()) {
                case "token":
                    builder.authentication(AuthenticationFactory.token(AUTH_PARAMS));
                    break;
                case "oauth2":
                    builder.authentication(AuthenticationFactory.create(
                            "org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2",
                            AUTH_PARAMS));
                    break;
                case "tls":
                    builder.authentication(AuthenticationFactory.create(
                            "org.apache.pulsar.client.impl.auth.AuthenticationTls",
                            AUTH_PARAMS));
                    break;
                case "basic":
                    if (BROKER_USERNAME != null && BROKER_PASSWORD != null) {
                        String basicAuth = String.format(
                                "{\"userId\":\"%s\",\"password\":\"%s\"}", BROKER_USERNAME, BROKER_PASSWORD);
                        builder.authentication(AuthenticationFactory.create(
                                "org.apache.pulsar.client.impl.auth.AuthenticationBasic",
                                basicAuth));
                    } else {
                        log.warn("AUTH_NAME=basic but BROKER_USERNAME / BROKER_PASSWORD not set — skipping auth");
                    }
                    break;
                default:
                    log.warn("Unknown AUTH_NAME '{}' — no authentication configured", AUTH_NAME);
            }
        } catch (Exception e) {
            log.error("Failed to configure Pulsar authentication", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void closeQuietly(String name, ThrowingRunnable action) {
        try {
            action.run();
            log.info("{} closed", name);
        } catch (Exception e) {
            log.error("Error closing {}: {}", name, e.getMessage());
        }
    }
}
