package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
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

        // Start Camel context
        try {
            camelContext.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Camel context", e);
        }
    }

    @Override
    public ProcessingResult<?> onMessage(ConnectorMessage message) {
        return processMessage(message);
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
    public ProcessingResult<?> processMessage(ConnectorMessage connectorMessage) {
        String topic = connectorMessage.getTopic();
        String tenant = connectorMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.isLogPayload()) {
            if (connectorMessage.getPayload() != null) {
                String payload = new String(connectorMessage.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
                log.info("{} - PROCESSING: message on topic: [{}], payload: {}", tenant, topic, payload);
            }
        }

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Early return for system topics or null payload
        if (topic == null || topic.startsWith("$SYS") || connectorMessage.getPayload() == null) {
            return result;
        }

        // Declare final variables for use in lambda
        final List<Mapping> resolvedMappings;
        final Qos finalConsolidatedQos;
        final int maxCPUTime;

        try {
            // Resolve mappings for the topic
            List<Mapping> tempMappings = mappingService.resolveMappingInbound(tenant, topic);
            resolvedMappings = tempMappings; // Now final

            Qos tempQos = connectorClient.determineMaxQosInbound(resolvedMappings);
            finalConsolidatedQos = tempQos; // Now final

            result.setConsolidatedQos(finalConsolidatedQos);

            // Set max CPU time if code-based mappings exist
            int tempMaxCPUTime = 0;
            for (Mapping mapping : resolvedMappings) {
                if (mapping.isSubstitutionsAsCode()) {
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
                Exchange exchange = createExchange(connectorMessage, resolvedMappings); // Now can use final variable
                Exchange resultExchange = producerTemplate.send("direct:processInboundMessage", exchange);

                @SuppressWarnings("unchecked")
                List<ProcessingContext<Object>> contexts = resultExchange.getIn().getHeader("processedContexts",
                        List.class);
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                log.error("{} - Error processing message through Camel routes: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Camel processing failed", e);
            }
        });

        result.setProcessingResult((Future) futureProcessingResult);
        return result;
    }

    /**
     * Create Camel Exchange from ConnectorMessage and resolved mappings
     */
    private Exchange createExchange(ConnectorMessage message, List<Mapping> resolvedMappings) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();

        // Set the ConnectorMessage as the body
        camelMessage.setBody(message);

        // Set headers for processing
        camelMessage.setHeader("tenant", message.getTenant());
        camelMessage.setHeader("topic", message.getTopic());
        camelMessage.setHeader("connectorIdentifier", message.getConnectorIdentifier());
        camelMessage.setHeader("client", message.getClient());
        camelMessage.setHeader("mappings", resolvedMappings);
        camelMessage.setHeader("connectorMessage", message);
        camelMessage.setHeader("serviceConfiguration", configurationRegistry.getServiceConfiguration(message.getTenant()));

        // Convert headers array to map if present
        if (message.getHeaders() != null) {
            Map<String, String> headerMap = parseHeaders(message.getHeaders());
            camelMessage.setHeader("originalHeaders", headerMap);
        }

        // Set payload information
        camelMessage.setHeader("payloadBytes", message.getPayload());
        if (message.getPayload() != null) {
            camelMessage.setHeader("payloadString", new String(message.getPayload()));
        }

        // Set connector information
        camelMessage.setHeader("connectorClient", connectorClient);
        camelMessage.setHeader("configurationRegistry", configurationRegistry);

        return exchange;
    }

    /**
     * Parse headers array to map
     */
    private Map<String, String> parseHeaders(String[] headers) {
        Map<String, String> headerMap = new HashMap<>();
        for (String header : headers) {
            String[] parts = header.split(":", 2);
            if (parts.length == 2) {
                headerMap.put(parts[0].trim(), parts[1].trim());
            }
        }
        return headerMap;
    }

    /**
     * Cleanup resources
     */
    public void shutdown() {
        try {
            if (producerTemplate != null) {
                producerTemplate.stop();
            }
            if (camelContext != null) {
                camelContext.stop();
            }
        } catch (Exception e) {
            log.error("Error shutting down Camel components: {}", e.getMessage(), e);
        }
    }
}