package dynamic.mapper.connector.pulsar;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;

import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTServicePulsarCallback implements MessageListener<byte[]> {

    /**
     * Maximum number of consecutive failures (timeout or error) before treating
     * a specific message as a poison pill and ACKing it to break the infinite redelivery loop.
     * After this threshold, the message is acknowledged (discarded) and a hard error is logged.
     * The counter is reset to zero on every successful message processing.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Maximum time a mapping can use CPU-time to process end to end. Only used in error cases,
     *  otherwise configured timeout is used
     */
    private static final int MAX_PROCESSING_TIMEOUT = 30000;


    private GenericMessageCallback genericMessageCallback;
    private String tenant;
    private String connectorIdentifier;
    private String connectorName;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService virtualThreadPool;
    /**
     * Counts consecutive failures per message ID (timeout / error) without a successful processing.
     * Each message is tracked independently to prevent one failing message from affecting others.
     * When a message's counter exceeds {@link #MAX_CONSECUTIVE_FAILURES}, it is treated as a poison pill
     * and ACKed to break an infinite redelivery loop.
     */
    private final Map<String, AtomicInteger> failureCountPerMessage = new ConcurrentHashMap<>();

    public MQTTServicePulsarCallback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
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
            log.info(
                    "{} - INITIAL: message {} on topic: [{}], connector: {}, {}",
                    tenant, messageId, towardsDeviceTopic, connectorName, connectorIdentifier);
        }

        // Process the message
        ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);

        int timeout = processedResults.getPipelineTimeoutMS();

        //TODO For what do we have this log output?
        /*
        if (serviceConfiguration.getLogPayload()) {
            log.info(
                    "{} - PREPARING_RESULTS: message {} on topic: [{}], connector {}",
                    tenant, messageId, towardsDeviceTopic, connectorIdentifier);
        }
         */

        // Use the provided virtualThreadPool instead of creating a new thread
        virtualThreadPool.submit(() -> {
            long effectiveTimeout = timeout;
            try {
                // Wait for the future to complete.
                // Multiply timeout by (attempt + 1) so each retransmission gets progressively
                // more processing time — useful when the first timeout was caused by a slow server.
                // attempt = current failure count for this message:
                //   0 → first try   → 1× timeout
                //   1 → 2nd try     → 2× timeout
                //   …  capped at MAX_CONSECUTIVE_FAILURES × timeout
                List<? extends ProcessingContext<?>> results;
                if (timeout > 0) {
                    int attempt = failureCountPerMessage.getOrDefault(messageId, new AtomicInteger(0)).get();
                    effectiveTimeout = Math.min(
                            (long) timeout * (attempt + 1),
                            MAX_PROCESSING_TIMEOUT);
                    if (attempt > 0) {
                        log.info("{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                tenant, attempt + 1, effectiveTimeout, effectiveTimeout, connectorIdentifier);
                    }
                    results = processedResults.getProcessingResult().get(effectiveTimeout, TimeUnit.MILLISECONDS);
                } else {
                    results = processedResults.getProcessingResult().get(MAX_PROCESSING_TIMEOUT, TimeUnit.MILLISECONDS);
                }

                // Check for errors in results
                boolean hasErrors = false;
                int httpStatusCode = 0;
                if (results != null) {
                    for (ProcessingContext<?> context : results) {
                        if (context.hasError()) {
                            for (Exception error : context.getErrors()) {
                                if (error instanceof ProcessingException) {
                                    if (((ProcessingException) error).getOriginException() instanceof SDKException) {
                                        if (((SDKException) ((ProcessingException) error).getOriginException())
                                                .getHttpStatus() > httpStatusCode) {
                                            httpStatusCode = ((SDKException) ((ProcessingException) error)
                                                    .getOriginException()).getHttpStatus();
                                        }
                                    }
                                }
                            }
                            hasErrors = true;
                            log.error("{} - Error in processing context for topic: [{}]", tenant, towardsDeviceTopic);
                            break;
                        }
                    }
                }

                if (!hasErrors) {
                    // No errors found, acknowledge the message and reset failure counter for this message
                    failureCountPerMessage.remove(messageId);
                    if (serviceConfiguration.getLogPayload()) {
                        log.info("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                                tenant, towardsDeviceTopic, connectorIdentifier);
                    }
                    consumer.acknowledge(message);
                } else if (httpStatusCode < 500) {
                    // Client errors - acknowledge to prevent redelivery and reset failure counter
                    failureCountPerMessage.remove(messageId); // client-side error is not a transient failure
                    log.warn("{} - END: Sending ack due to client error for Pulsar message: topic: [{}], connector: {}",
                            tenant, towardsDeviceTopic, connectorIdentifier);
                    consumer.acknowledge(message);
                } else {
                    // Server error (>=500): check poison-pill threshold
                    log.warn(
                            "{} - END: Server error (HTTP {}), sending negative ack for Pulsar redelivery. topic: [{}], connector: {}",
                            tenant, httpStatusCode, towardsDeviceTopic, connectorIdentifier);
                    handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
                }
            } catch (InterruptedException | ExecutionException e) {
                // Processing failed, check poison-pill threshold
                log.warn("{} - END: Was interrupted for Pulsar message: topic: [{}], connector: {}",
                        tenant, towardsDeviceTopic, connectorIdentifier);
                handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
            } catch (TimeoutException e) {
                // Cancel the processing task to stop execution
                log.warn("{} - Timeout occurred, initiating cancellation of processing task, connector: {}",
                        tenant, connectorIdentifier);
                var cancelResult = processedResults.cancelProcessing();
                log.info("{} - Cancellation result: future was cancelled={}, connector: {}", tenant, cancelResult,
                        connectorIdentifier);

                // Wait for the future to actually terminate after cancellation.
                // The active cancellation checks in C8YAgent.createMEAO() should prevent any new HTTP calls,
                // allowing threads to exit quickly. We wait up to 2 seconds as a fail-safe in case a thread
                // is already mid-flight in an HTTP call that cannot be interrupted immediately.
                boolean futureCompleted = false;
                int maxWaitIterations = 20; // 20 × 100ms = 2 seconds
                for (int i = 0; i < maxWaitIterations; i++) {
                    if (processedResults.getProcessingResult().isDone()) {
                        futureCompleted = true;
                        log.info("{} - Future completed after {}ms wait, connector: {}", tenant, (i + 1) * 100, connectorIdentifier);
                        break;
                    }
                    try {
                        Thread.sleep(100); //NOSONAR intentional wait for future completion
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("{} - Interrupted while waiting for future completion, connector: {}", tenant, connectorIdentifier);
                        break;
                    }
                }

                if (!futureCompleted) {
                    log.error("{} - Future did NOT complete within 2 seconds after cancellation! " +
                            "This indicates that a thread is stuck in a blocking operation that cannot be interrupted. " +
                            "Check for long-running HTTP calls or other blocking I/O. connector: {}", tenant, connectorIdentifier);
                }

                log.warn(
                        "{} - END: Processing timed out after {} ms, sending negative ack for Pulsar redelivery. connector: {}, cancel result: {}, future completed: {}",
                        tenant, effectiveTimeout, connectorIdentifier, cancelResult, futureCompleted);
                handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
            } catch (PulsarClientException e) {
                log.error("{} - Error acknowledging Pulsar message: topic: [{}], connector: {}",
                        tenant, towardsDeviceTopic, connectorIdentifier, e);
            }
            return null;
        });
    }

    /**
     * Handles failure-to-acknowledge or poison-pill detection for unreliable messages.
     * Each message is tracked independently by its ID, so one failing message does not affect others.
     * <ul>
     *   <li>If the consecutive-failure counter for this specific message has reached
     *       {@link #MAX_CONSECUTIVE_FAILURES}: the message is treated as a <em>poison pill</em>
     *       — it is ACKed (discarded) and a hard error is logged. This breaks an infinite redelivery
     *       loop caused by a permanently unprocessable message.</li>
     *   <li>Otherwise the message is negative-ACKed to trigger Pulsar's native redelivery mechanism.</li>
     * </ul>
     *
     * @param consumer  the Pulsar consumer (needed for ack/nack)
     * @param message   the unacknowledged Pulsar message
     * @param messageId the unique message ID for tracking failures
     * @param topic     human-readable topic string for logging
     */
    private void handleFailureOrPoisonPill(Consumer<byte[]> consumer, Message<byte[]> message,
                                           String messageId, String topic) {
        // Track failures per message ID independently
        int failureCount = failureCountPerMessage
            .computeIfAbsent(messageId, k -> new AtomicInteger(0))
            .incrementAndGet();

        if (failureCount >= MAX_CONSECUTIVE_FAILURES) {
            log.error("{} - POISON PILL: {} consecutive failures without success — ACKing message to prevent "
                    + "infinite redelivery loop. Message is discarded. messageId: {}, topic: [{}], connector: {}",
                    tenant, failureCount, messageId, topic, connectorIdentifier);
            failureCountPerMessage.remove(messageId);
            try {
                consumer.acknowledge(message);
            } catch (PulsarClientException e) {
                log.error("{} - Error acknowledging poison-pill Pulsar message: messageId: {}, topic: [{}], connector: {}",
                        tenant, messageId, topic, connectorIdentifier, e);
            }
            return;
        }

        if (failureCount > 1) {
            log.warn("{} - Redelivery attempt {} for message: {}, topic: [{}], connector: {}",
                    tenant, failureCount, messageId, topic, connectorIdentifier);
        }

        // Negative-ACK triggers Pulsar's native redelivery mechanism
        consumer.negativeAcknowledge(message);
    }
}

