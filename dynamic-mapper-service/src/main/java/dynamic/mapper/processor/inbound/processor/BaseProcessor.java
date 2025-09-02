package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor implements Processor {

    public abstract void process(Exchange exchange) throws Exception;

    protected ProcessingContext<Object> createProcessingContextAsObject(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration) {
        return ProcessingContext.<Object>builder()
                .rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .client(connectorMessage.getClient())
                .mappingType(mapping.mappingType)
                .mapping(mapping)
                .sendPayload(connectorMessage.isSendPayload())
                .tenant(tenant)
                .supportsMessageContext(
                        connectorMessage.isSupportsMessageContext() && mapping.supportsMessageContext)
                .key(connectorMessage.getKey())
                .api(mapping.targetAPI).build();
    }

    protected ProcessingContext<byte[]> createProcessingContextAsByteArray(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration) {
        return ProcessingContext.<byte[]>builder().rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .client(connectorMessage.getClient())
                .sendPayload(connectorMessage.isSendPayload())
                .tenant(tenant)
                .supportsMessageContext(
                        connectorMessage.isSupportsMessageContext() && mapping.supportsMessageContext)
                .key(connectorMessage.getKey())
                .build();
    }

}
