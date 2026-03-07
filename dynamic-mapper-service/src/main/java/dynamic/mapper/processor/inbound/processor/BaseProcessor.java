package dynamic.mapper.processor.inbound.processor;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.camel.Exchange;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.CommonProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor extends CommonProcessor {

    public abstract void process(Exchange exchange) throws Exception;

    protected ProcessingContext<Object> createProcessingContextAsObject(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration, Boolean testing) {
        return ProcessingContext.<Object>builder()
                .rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .clientId(connectorMessage.getClientId())
                .mappingType(mapping.getMappingType())
                .serviceConfiguration(serviceConfiguration)
                .mapping(mapping)
                .sendPayload(connectorMessage.getSendPayload())
                .testing(testing)
                .tenant(tenant)
                .key(connectorMessage.getKey())
                .api(mapping.getTargetAPI()).build();
    }

    protected ProcessingContext<byte[]> createProcessingContextAsByteArray(String tenant, Mapping mapping,
            ConnectorMessage connectorMessage, ServiceConfiguration serviceConfiguration, Boolean testing) {
        return ProcessingContext.<byte[]>builder().rawPayload(connectorMessage.getPayload())
                .topic(connectorMessage.getTopic())
                .clientId(connectorMessage.getClientId())
                .mappingType(mapping.getMappingType())
                .serviceConfiguration(serviceConfiguration)
                .mapping(mapping)
                .sendPayload(connectorMessage.getSendPayload())
                .testing(testing)
                .tenant(tenant)
                .key(connectorMessage.getKey())
                .api(mapping.getTargetAPI())
                .build();
    }

    protected void validateProcessingCache(ProcessingContext<?> context) {
        // if there are too few devices identified, then we replicate the first device
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        String entryWithMaxSubstitutes = processingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();
        int countMaxEntries = processingCache.get(entryWithMaxSubstitutes).size();

        List<String> pathsTargetForDeviceIdentifiers = context.getPathsTargetForDeviceIdentifiers();
        String firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.size() > 0
                ? pathsTargetForDeviceIdentifiers.get(0)
                : null;

        List<SubstituteValue> deviceEntries = processingCache
                .get(firstPathTargetForDeviceIdentifiers);
        SubstituteValue toDuplicate = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxEntries) {
            deviceEntries.add(toDuplicate);
        }
    }


}
