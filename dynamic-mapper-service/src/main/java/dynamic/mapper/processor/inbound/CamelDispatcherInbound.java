package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;

@Component
public class CamelDispatcherInbound implements GenericMessageCallback {
    
    private final ProducerTemplate producerTemplate;
    private final CamelContext camelContext;
    private final ExecutorService executorService;
    
    public CamelDispatcherInbound(CamelContext camelContext) {
        this.camelContext = camelContext;
        this.producerTemplate = camelContext.createProducerTemplate();
        this.executorService = Executors.newCachedThreadPool();
    }
    
    @Override
    public ProcessingResult<?> onMessage(ConnectorMessage message) {
        try {
            // For async processing
            Future<List<ProcessingContext<Object>>> processingFuture = executorService.submit(() -> {
                try {
                    Exchange exchange = createExchange(message);
                    Exchange result = producerTemplate.send("direct:processInboundMessage", exchange);
                    
                    @SuppressWarnings("unchecked")
                    List<ProcessingContext<Object>> contexts = result.getIn().getHeader("processedContexts", List.class);
                    return contexts != null ? contexts : new ArrayList<>();
                    
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            
            // Build ProcessingResult with correct fields
            return ProcessingResult.<Object>builder()
                .processingResult(processingFuture)
                .consolidatedQos(determineQos(message))
                .maxCPUTimeMS(5000) // 5 seconds default timeout
                .build();
                
        } catch (Exception e) {
            // Build error result
            return ProcessingResult.<Object>builder()
                .error(e)
                .maxCPUTimeMS(0)
                .build();
        }
    }
    
    // Alternative: Synchronous processing
    public ProcessingResult<?> onMessageSync(ConnectorMessage message) {
        try {
            Exchange exchange = createExchange(message);
            Exchange result = producerTemplate.send("direct:processInboundMessage", exchange);
            
            @SuppressWarnings("unchecked")
            List<ProcessingContext<Object>> contexts = result.getIn().getHeader("processedContexts", List.class);
            
            // Create completed future with results
            CompletableFuture<List<ProcessingContext<Object>>> completedFuture = 
                CompletableFuture.completedFuture(contexts != null ? contexts : new ArrayList<>());
            
            return ProcessingResult.<Object>builder()
                .processingResult(completedFuture)
                .consolidatedQos(determineQos(message))
                .maxCPUTimeMS(calculateProcessingTime(contexts))
                .build();
                
        } catch (Exception e) {
            return ProcessingResult.<Object>builder()
                .error(e)
                .maxCPUTimeMS(0)
                .build();
        }
    }
    
    private Qos determineQos(ConnectorMessage message) {
        String connectorId = message.getConnectorIdentifier();
        if (connectorId != null) {
            switch (connectorId.toLowerCase()) {
                case "mqtt":
                    return Qos.AT_LEAST_ONCE;
                case "kafka":
                    return Qos.EXACTLY_ONCE;
                default:
                    return Qos.AT_MOST_ONCE;
            }
        }
        return Qos.AT_LEAST_ONCE; // Default
    }
    
    private int calculateProcessingTime(List<ProcessingContext<Object>> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 100; // Minimum time
        }
        
        // Calculate based on number of contexts and their complexity
        int baseTime = 500; // Base processing time
        int perContextTime = 200; // Time per context
        return baseTime + (contexts.size() * perContextTime);
    }
    
    private Exchange createExchange(ConnectorMessage message) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();
        
        camelMessage.setBody(message);
        camelMessage.setHeader("tenant", message.getTenant());
        camelMessage.setHeader("topic", message.getTopic());
        camelMessage.setHeader("connectorIdentifier", message.getConnectorIdentifier());
        camelMessage.setHeader("client", message.getClient());
        
        // Convert headers array to map if present
        if (message.getHeaders() != null) {
            Map<String, String> headerMap = parseHeaders(message.getHeaders());
            camelMessage.setHeader("originalHeaders", headerMap);
        }
        
        // Set payload
        camelMessage.setHeader("payloadBytes", message.getPayload());
        if (message.getPayload() != null) {
            camelMessage.setHeader("payloadString", new String(message.getPayload()));
        }
        
        return exchange;
    }
    
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

    @Override
    public void onClose(String closeMessage, Throwable closeException) {
    }

    @Override
    public void onError(Throwable errorException) {
    }
}