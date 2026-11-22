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
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.connector.pulsar;

import java.util.concurrent.ExecutorService;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.callback.MessageFailureTracker;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base for Pulsar broker callbacks.
 *
 * <p>Provides shared infrastructure used by both {@link PulsarCallback} (plain Pulsar)
 * and {@link MQTTServicePulsarCallback} (Cumulocity MQTT Service over Pulsar):
 * <ul>
 *   <li>Common fields and constructor wiring</li>
 *   <li>{@link #cancelAndDrain(ProcessingResultWrapper)} — cancel a processing future and wait
 *       up to 2 seconds for it to drain before releasing the Pulsar ack slot</li>
 *   <li>{@link #settle(Consumer, Message, String, String, AckOutcome)} — acknowledge, negative-acknowledge
 *       or, after {@code maxProcessingAttempts} failures, discard a message as a poison pill</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractPulsarCallback implements MessageListener<byte[]> {

    protected final GenericMessageCallback genericMessageCallback;
    protected final String tenant;
    protected final String connectorIdentifier;
    protected final String connectorName;
    protected final ServiceRegistry serviceRegistry;
    protected final ExecutorService virtualThreadPool;

    /**
     * Per-message failure counter (message ID as key). After {@code maxProcessingAttempts} failures the
     * message is treated as a poison pill, ACKed (discarded), and a hard error is logged to break an
     * infinite redelivery loop.
     */
    protected final MessageFailureTracker failureTracker;

    protected AbstractPulsarCallback(
            String tenant,
            ServiceRegistry serviceRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceRegistry = serviceRegistry;
        this.virtualThreadPool = serviceRegistry.getVirtualThreadPool();
        this.failureTracker = MessageFailureTracker.forConfiguration(() -> serviceRegistry.getServiceConfiguration(tenant));
    }

    /**
     * The tenant's current configuration, looked up per use: the registry replaces the object when the
     * configuration is saved, so a copy captured at construction kept the old {@code logPayload}.
     */
    protected ServiceConfiguration serviceConfiguration() {
        return serviceRegistry.getServiceConfiguration(tenant);
    }

    /**
     * Cancels an in-flight processing task and waits up to 2 seconds for the future to drain.
     *
     * <p>The active-cancellation checks in {@code C8YAgent.createMEAO()} prevent new HTTP calls
     * from starting, so threads typically exit quickly. The 2-second cap is a fail-safe for
     * threads already mid-flight in an HTTP call that cannot be interrupted immediately.
     *
     * @param processedResults the result wrapper whose future should be cancelled
     * @return true if the future completed within the 2-second window, false otherwise
     */
    protected boolean cancelAndDrain(ProcessingResultWrapper<?> processedResults) {
        boolean drained = processedResults.cancelAndDrain(ProcessingResultWrapper.DEFAULT_DRAIN_MILLIS);
        if (!drained) {
            log.error("{} - Processing thread still running after cancellation, connector: {}",
                    tenant, connectorIdentifier);
        }
        return drained;
    }

    /**
     * Settles a message after its pipeline ran, as {@link MessageFailureTracker#settle} decides: acknowledge it when
     * it was processed, negative-acknowledge it for redelivery when it failed, and once it failed
     * {@code maxProcessingAttempts} times acknowledge (discard) it as a poison pill to break an infinite redelivery
     * loop. The negative acknowledge carries Pulsar's own redelivery delay.
     *
     * @param consumer  the Pulsar consumer (for ack/nack)
     * @param message   the unacknowledged Pulsar message
     * @param messageId unique message identifier for per-message failure tracking
     * @param topic     human-readable topic string for logging
     * @param outcome   what the pipeline result says
     */
    protected void settle(Consumer<byte[]> consumer, Message<byte[]> message, String messageId, String topic,
            AckOutcome outcome) {
        switch (failureTracker.settle(messageId, outcome)) {
            case ACK:
                log.debug("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                        tenant, topic, connectorIdentifier);
                acknowledge(consumer, message, messageId, topic);
                return;
            case DROP:
                log.error(
                        "{} - POISON PILL: {} failed attempts without success: ACKing message to prevent "
                                + "infinite redelivery loop. Message is discarded. messageId: {}, topic: [{}], connector: {}",
                        tenant, failureTracker.limit(), messageId, topic, connectorIdentifier);
                acknowledge(consumer, message, messageId, topic);
                return;
            default:
                log.warn("{} - Negative ack for Pulsar redelivery: message: {}, topic: [{}], connector: {}",
                        tenant, messageId, topic, connectorIdentifier);
                consumer.negativeAcknowledge(message);
        }
    }

    private void acknowledge(Consumer<byte[]> consumer, Message<byte[]> message, String messageId, String topic) {
        try {
            consumer.acknowledge(message);
        } catch (PulsarClientException e) {
            log.error("{} - Error acknowledging Pulsar message: messageId: {}, topic: [{}], connector: {}",
                    tenant, messageId, topic, connectorIdentifier, e);
        }
    }
}
