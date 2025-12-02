package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.SDKException;
import dynamic.mapper.processor.ProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CamelDispatcherInbound implements GenericMessageCallback {

    private final AConnectorClient connectorClient;
    private final ExecutorService virtualThreadPool;
    private final MappingService mappingService;
    private final ConfigurationRegistry configurationRegistry;

    private final ProducerTemplate producerTemplate;
    private final CamelContext camelContext;
    private final Timer inboundProcessingTimer;
    private final Counter inboundProcessingCounter;


    /**
     * Constructor matching DispatcherInbound signature
     */
    public CamelDispatcherInbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.connectorClient = connectorClient;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.mappingService = configurationRegistry.getMappingService();
        this.configurationRegistry = configurationRegistry;


        // Initialize Camel components
        this.camelContext = configurationRegistry.getCamelContext();
        this.producerTemplate = camelContext.createProducerTemplate();
        this.inboundProcessingTimer = Timer.builder("dynmapper_inbound_processing_time")
                .tag("tenant", connectorClient.getTenant())
                .tag("connector", connectorClient.getConnectorIdentifier())
                .description("Processing time of inbound messages").register(Metrics.globalRegistry);
        this.inboundProcessingCounter = Counter.builder("dynmapper_inbound_message_total")
                .tag("tenant", connectorClient.getTenant()).description("Total number of inbound messages")
                .tag("connector", connectorClient.getTenant()).register(Metrics.globalRegistry);
    }

    @Override
    public ProcessingResultWrapper<?> onMessage(ConnectorMessage message) {
        return processMessage(message, null);
    }

    @Override
    public void onClose(String closeMessage, Throwable closeException) {
        // Handle connection close
    }

    @Override
    public void onError(Throwable errorException) {
        // Handle connection errors
        log.error("Connection error: {}", errorException.getMessage(), errorException);
    }

    /**
     * Process message using Camel routes - matches DispatcherInbound.processMessage
     * signature
     */
    private ProcessingResultWrapper<?> processMessage(ConnectorMessage connectorMessage, Mapping testMapping) {
        Timer.Sample timer = Timer.start(Metrics.globalRegistry);
        boolean testing = testMapping != null;
        String topic = connectorMessage.getTopic();
        String tenant = connectorMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.getLogPayload()) {
            if (connectorMessage.getPayload() != null) {
                String payload = new String(connectorMessage.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("{} - PROCESSING: message on topic: [{}], payload: {}", tenant, topic, payload);
            }
        }
        this.inboundProcessingCounter.increment();

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Early return for system topics or null payload
        if (topic == null || topic.startsWith("$SYS") || connectorMessage.getPayload() == null) {
            return result;
        }

        // Declare final variables for use in lambda
        List<Mapping> resolvedMappings;
        int maxCPUTime;

        try {
            // Resolve mappings for the topic
            if (testMapping != null) {
                resolvedMappings = new ArrayList<>();
                resolvedMappings.add(testMapping);
            } else {
                resolvedMappings = mappingService.resolveMappingInbound(tenant, topic);
            }

            result.setConsolidatedQos(connectorClient.determineMaxQosInbound(resolvedMappings));

            // Set max CPU time if code-based mappings exist
            int tempMaxCPUTime = 0;
            for (Mapping mapping : resolvedMappings) {
                if (mapping.isTransformationAsCode()) {
                    tempMaxCPUTime = serviceConfiguration.getMaxCPUTimeMS();
                    break;
                }
            }
            maxCPUTime = tempMaxCPUTime; // Now final
            result.setMaxCPUTimeMS(maxCPUTime);

        } catch (Exception e) {
            log.warn("{} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
                    tenant, topic);
            log.debug(e.getMessage(), e);
            return result;
        }

        // Process using Camel routes asynchronously
        Future<List<ProcessingContext<Object>>> futureProcessingResult = virtualThreadPool.submit(() -> {
            try {
                Exchange exchange = createExchange(connectorMessage, resolvedMappings, testing); // Now can use final variable
                Exchange resultExchange = producerTemplate.send("direct:processInboundMessage", exchange);

                @SuppressWarnings("unchecked")
                List<ProcessingContext<Object>> contexts = resultExchange.getIn().getHeader("processedContexts",
                        List.class);
                boolean resend = false;
                if (contexts != null) {
                    for (ProcessingContext<?> context : contexts) {
                        int httpStatus = 0;
                        if (context.hasError()) {
                            for (Exception error : context.getErrors()) {
                                if (error instanceof ProcessingException) {
                                    if (((ProcessingException) error)
                                            .getOriginException() instanceof SDKException) {
                                        if (((SDKException) ((ProcessingException) error).getOriginException())
                                                .getHttpStatus() > httpStatus) {
                                            httpStatus = ((SDKException) ((ProcessingException) error).getOriginException())
                                                    .getHttpStatus();
                                        }
                                    }
                                }
                            }
                            if(httpStatus == 422) {
                                log.info("{} - Removing device from Identity Cache with external ID: {}",
                                        tenant, context.getCurrentRequest().getExternalId());
                                ID identity = new ID(context.getCurrentRequest().getExternalIdType(), context.getExternalId());
                                this.connectorClient.getC8yAgent().removeDeviceFromInboundExternalIdCache(tenant, identity);
                                if(context.getMapping().getCreateNonExistingDevice())
                                    resend = true;
                            }
                        }
                    }
                }
                if(resend) {
                    if (serviceConfiguration.getLogPayload())
                        log.info("{} - Resending message to C8Y due to previous 422 error with payload {}", tenant, connectorMessage.getPayload());
                    else
                        log.info("{} - Resending message to C8Y due to previous 422 error", tenant);
                    exchange = createExchange(connectorMessage, resolvedMappings, testing);
                    resultExchange = producerTemplate.send("direct:processInboundMessage", exchange);
                    contexts = resultExchange.getIn().getHeader("processedContexts",
                            List.class);
                }
                // Stop the timer
                timer.stop(inboundProcessingTimer);
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                log.error("{} - Error processing inbound message through Camel routes: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Camel processing failed", e);
            }
        });

        result.setProcessingResult((Future) futureProcessingResult);

        return result;
    }

    /**
     * Create Camel Exchange from ConnectorMessage and resolved mappings
     */
    private Exchange createExchange(ConnectorMessage message, List<Mapping> resolvedMappings, boolean testing) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();

        // Set the ConnectorMessage as the body
        camelMessage.setBody(message);

        // Set headers for processing
        camelMessage.setHeader("connectorIdentifier", message.getConnectorIdentifier());
        camelMessage.setHeader("tenant", message.getTenant());
        camelMessage.setHeader("client", message.getClientId());
        camelMessage.setHeader("testing", testing);
        camelMessage.setHeader("mappings", resolvedMappings);
        camelMessage.setHeader("connectorMessage", message);
        camelMessage.setHeader("serviceConfiguration",
                configurationRegistry.getServiceConfiguration(message.getTenant()));

        // Set payload information
        camelMessage.setHeader("payloadBytes", message.getPayload());
        if (message.getPayload() != null) {
            camelMessage.setHeader("payloadString", new String(message.getPayload()));
        }

        return exchange;
    }

    @Override
    public ProcessingResultWrapper<?> onTestMessage(ConnectorMessage message, Mapping testMapping) {
        return processMessage(message, testMapping);
    }
}