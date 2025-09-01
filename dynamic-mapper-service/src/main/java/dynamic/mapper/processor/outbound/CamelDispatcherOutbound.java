package dynamic.mapper.processor.outbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;

import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.C8YMessage;

import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.service.MappingService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CamelDispatcherOutbound implements NotificationCallback {

    @Getter
    private AConnectorClient connectorClient;
    private ExecutorService virtualThreadPool;
    private NotificationSubscriber notificationSubscriber;
    private MappingService mappingService;
    private ConfigurationRegistry configurationRegistry;
    private ProducerTemplate producerTemplate;
    private CamelContext camelContext;

    /**
     * Constructor matching DispatcherInbound signature
     */
    public CamelDispatcherOutbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.mappingService = configurationRegistry.getMappingService();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.connectorClient = connectorClient;
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();

        // Initialize Camel components
        this.camelContext = configurationRegistry.getCamelContext();
        this.producerTemplate = camelContext.createProducerTemplate();
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, linked to connector: {}",
                connectorClient.getTenant(), connectorClient.getConnectorName());
        notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        // We don't care about UPDATES nor DELETES and ignore notifications if connector
        // is not connected
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
        if (!connectorClient.isConnected())
            log.warn("{} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());

        if (("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))
                && connectorClient.isConnected()) {
            if ("UPDATE".equals(notification.getOperation()) && notification.getApi().equals(API.OPERATION)) {
                log.info("{} - Update Operation message for connector: {} is received, ignoring it",
                        tenant, connectorClient.getConnectorName());
                return result;
            }
            C8YMessage c8yMessage = new C8YMessage();
            Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
            c8yMessage.setParsedPayload(parsedPayload);
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setOperation(notification.getOperation());
            String messageId = String.valueOf(parsedPayload.get("id"));
            c8yMessage.setMessageId(messageId);
            try {
                var expression = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expression.evaluate(parsedPayload);
                String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
                c8yMessage.setSourceId(sourceId);
            } catch (Exception e) {
                log.debug("Could not extract source.id: {}", e.getMessage());

            }
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            // TODO Return a future so it can be blocked for QoS 1 or 2
            return processMessage(c8yMessage);
        }
        return result;
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", connectorClient.getTenant());
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

    /**
     * Process message using Camel routes - matches DispatcherInbound.processMessage
     * signature
     */
    public ProcessingResult<?> processMessage(C8YMessage c8yMessage) {
        String tenant = c8yMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.isLogPayload()) {
            String payload = c8yMessage.getPayload();
            log.info("{} - PROCESSING: C8Y message, API: {}, device: {}. connector: {}, message id: {}",
                    tenant,
                    c8yMessage.getApi(), c8yMessage.getSourceId(),
                    connectorClient.getConnectorName(),
                    c8yMessage.getMessageId());

        }

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Declare final variables for use in lambda
        List<Mapping> resolvedMappings;
        int maxCPUTime;

        MappingStatus mappingStatusUnspecified = mappingService.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);

        if (c8yMessage.getPayload() != null) {
            try {
                resolvedMappings = mappingService.resolveMappingOutbound(tenant, c8yMessage, serviceConfiguration);
                consolidatedQos = connectorClient.determineMaxQosOutbound(resolvedMappings);
                result.setConsolidatedQos(consolidatedQos);

                // Check if at least one Code based mappings exits, then we nee to timeout the
                // execution
                for (Mapping mapping : resolvedMappings) {
                    if (mapping.isSubstitutionsAsCode()) {
                        result.setMaxCPUTimeMS(serviceConfiguration.getMaxCPUTimeMS());
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "{} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
                        tenant, e);
                log.debug(e.getMessage(), e);
                mappingStatusUnspecified.errors++;
                return result;
            }
        } else {
            return result;
        }

        // Process using Camel routes asynchronously
        Future<List<ProcessingContext<Object>>> futureProcessingResult = virtualThreadPool.submit(() -> {
            try {
                Exchange exchange = createExchange(c8yMessage, resolvedMappings); // Now can use final variable
                Exchange resultExchange = producerTemplate.send("direct:processOutboundMessage", exchange);

                @SuppressWarnings("unchecked")
                List<ProcessingContext<Object>> contexts = resultExchange.getIn().getHeader("processedContexts",
                        List.class);
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                log.error("{} - Error processing outbound message through Camel routes: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Camel processing failed", e);
            }
        });

        result.setProcessingResult((Future) futureProcessingResult);
        return result;
    }

    /**
     * Create Camel Exchange from ConnectorMessage and resolved mappings
     */
    private Exchange createExchange(C8YMessage message, List<Mapping> resolvedMappings) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();

        // Set the ConnectorMessage as the body
        camelMessage.setBody(message);

        // Set headers for processing
        camelMessage.setHeader("tenant", message.getTenant());
        camelMessage.setHeader("connectorIdentifier", getConnectorClient().getConnectorIdentifier());
        camelMessage.setHeader("source", message.getSourceId());
        camelMessage.setHeader("mappings", resolvedMappings);
        camelMessage.setHeader("c8yMessage", message);
        camelMessage.setHeader("serviceConfiguration",
                configurationRegistry.getServiceConfiguration(message.getTenant()));


        // Set payload information
        camelMessage.setHeader("payloadBytes", message.getPayload());
        if (message.getPayload() != null) {
            camelMessage.setHeader("payloadString", new String(message.getPayload()));
        }

        return exchange;
    }
}