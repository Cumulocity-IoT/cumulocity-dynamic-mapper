package dynamic.mapper.connector.pulsar;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar callback for the Cumulocity MQTT Service (messages arriving via the C8Y Pulsar broker).
 *
 * <p>Inherits shared infrastructure from {@link AbstractPulsarCallback}:
 * poison-pill detection, 2-second future drain after cancellation, and common fields.
 *
 * <p>Additional behavior specific to the MQTT Service:
 * <ul>
 *   <li>Separates the internal Pulsar topic ({@code towardsDeviceTopic}) from the
 *       MQTT topic carried as a message property</li>
 *   <li>Exponential timeout scaling on retransmission</li>
 *   <li>JS CPU-timeout early-cancellation guard ({@code cancellationRequested})</li>
 * </ul>
 */
@Slf4j
public class MQTTServicePulsarCallback extends AbstractPulsarCallback {

    public MQTTServicePulsarCallback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName) {
        super(tenant, configurationRegistry, callback, connectorIdentifier, connectorName);
    }

    @Override
    public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
        String towardsDeviceTopic = message.getTopicName();
        String topic = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_TOPIC);
        String client = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_CLIENT_ID);
        byte[] payloadBytes = message.getData();
        String messageId = message.getMessageId().toString();

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .clientId(client)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();

        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - INITIAL: message {} on topic: [{}], connector: {}, {}",
                    tenant, messageId, towardsDeviceTopic, connectorName, connectorIdentifier);
        }

        ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
        int timeout = processedResults.getPipelineTimeoutMS();
        int pipelineTimeoutMS = serviceConfiguration.getPipelineTimeoutMS() != null
                ? serviceConfiguration.getPipelineTimeoutMS() : 30_000;

        virtualThreadPool.submit(() -> {
            long effectiveTimeout = timeout;
            try {
                List<? extends ProcessingContext<?>> results;
                if (timeout > 0) {
                    int attempt = failureCountPerMessage
                            .getOrDefault(messageId, new AtomicInteger(0)).get();
                    effectiveTimeout = Math.min(
                            (long) timeout * (attempt + 1),
                            pipelineTimeoutMS);
                    if (attempt > 0) {
                        log.info(
                                "{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                tenant, attempt + 1, effectiveTimeout, timeout, connectorIdentifier);
                    }
                    results = processedResults.getProcessingResult().get(effectiveTimeout, TimeUnit.MILLISECONDS);
                } else {
                    results = processedResults.getProcessingResult().get(pipelineTimeoutMS, TimeUnit.MILLISECONDS);
                }

                // JS CPU timeout may have fired before the wall-clock timeout expired.
                // The future completes early with cancellationRequested=true — do NOT ACK.
                if (processedResults.getCancellationRequested().get()) {
                    log.warn(
                            "{} - JS CPU timeout fired: processing cancelled before wall-clock timeout, not ACKing. connector: {}",
                            tenant, connectorIdentifier);
                    handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
                    return null;
                }

                int httpStatusCode = ProcessingResultHelper.extractMaxHttpStatus(
                        results, tenant, towardsDeviceTopic, log);

                if (httpStatusCode < 0) {
                    failureCountPerMessage.remove(messageId);
                    if (serviceConfiguration.getLogPayload()) {
                        log.info("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                                tenant, towardsDeviceTopic, connectorIdentifier);
                    }
                    consumer.acknowledge(message);
                } else if (httpStatusCode < 500) {
                    failureCountPerMessage.remove(messageId);
                    log.warn("{} - END: Sending ack due to client error for Pulsar message: topic: [{}], connector: {}",
                            tenant, towardsDeviceTopic, connectorIdentifier);
                    consumer.acknowledge(message);
                } else {
                    log.warn(
                            "{} - END: Server error (HTTP {}), sending negative ack for Pulsar redelivery. topic: [{}], connector: {}",
                            tenant, httpStatusCode, towardsDeviceTopic, connectorIdentifier);
                    handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
                }
            } catch (InterruptedException | ExecutionException e) {
                log.warn("{} - END: Was interrupted for Pulsar message: topic: [{}], connector: {}",
                        tenant, towardsDeviceTopic, connectorIdentifier);
                handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
            } catch (TimeoutException e) {
                log.warn("{} - Timeout occurred, initiating cancellation of processing task, connector: {}",
                        tenant, connectorIdentifier);
                boolean futureCompleted = cancelAndDrain(processedResults);
                log.warn(
                        "{} - END: Processing timed out after {}ms, sending negative ack for Pulsar redelivery. connector: {}, future completed: {}",
                        tenant, effectiveTimeout, connectorIdentifier, futureCompleted);
                handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
            } catch (PulsarClientException e) {
                log.error("{} - Error acknowledging Pulsar message: topic: [{}], connector: {}",
                        tenant, towardsDeviceTopic, connectorIdentifier, e);
            }
            return null;
        });
    }
}
