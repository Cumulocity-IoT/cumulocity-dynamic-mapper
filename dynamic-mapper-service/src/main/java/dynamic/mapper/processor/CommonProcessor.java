package dynamic.mapper.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.ExternalId;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class CommonProcessor implements Processor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private C8YAgent c8yAgent;

    public abstract void process(Exchange exchange) throws Exception;

    /**
     * Evaluates an inventory filter against cached inventory data
     */
    protected boolean evaluateInventoryFilter(String tenant, String filterExpression, String sourceId,
            Boolean testing) {
        try {
            Map<String, Object> cachedInventoryContent = configurationRegistry.getC8yAgent()
                    .getMOFromInventoryCache(tenant, sourceId, testing);
            List<String> keyList = new ArrayList<>(cachedInventoryContent.keySet());
            log.info("{} - For object {} found following fragments in inventory cache {}",
                    tenant, sourceId, keyList);
            var expression = jsonata(filterExpression);
            Object result = expression.evaluate(cachedInventoryContent);

            if (result != null && Utils.isNodeTrue(result)) {
                log.info("{} - Found valid inventory for filter {}",
                        tenant, filterExpression);
                return true;
            } else {
                log.debug("{} - Not matching inventory filter {} for source {}",
                        tenant, filterExpression, sourceId);
                return false;
            }
        } catch (Exception e) {
            log.debug("Inventory filter evaluation error for {}: {}", filterExpression, e.getMessage());
            return false;
        }
    }

    protected String resolveDeviceIdentifier(CumulocityObject cumulocityMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        // First try externalSource
        if (cumulocityMessage.getExternalSource() != null) {
            return resolveFromExternalSource(cumulocityMessage.getExternalSource(), context, tenant);
        }

        // Fallback to mapping's generic device identifier
        return context.getMapping().getGenericDeviceIdentifier();
    }

    protected String resolveFromExternalSource(Object externalSourceObj, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalId> externalSources = JavaScriptInteropHelper.convertToExternalIdList(externalSourceObj);

        if (externalSources.isEmpty()) {
            throw new ProcessingException("External source is empty");
        }

        // Use the first external source for resolution
        ExternalId externalSource = externalSources.get(0);

        try {
            // Use C8YAgent to resolve external ID to global ID
            var globalId = c8yAgent.resolveExternalId2GlobalId(tenant,
                    new ID(externalSource.getType(), externalSource.getExternalId()),
                    context.getTesting());
            context.setExternalId(externalSource.getExternalId());

            if (globalId != null) {
                return globalId.getManagedObject().getId().getValue();
            } else {
                throw new ProcessingException("Could not resolve external ID: " + externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

    protected String resolveGlobalId2ExternalId(DeviceMessage deviceMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalId> externalSources = JavaScriptInteropHelper.convertToExternalIdList(deviceMessage.getExternalSource());
        // Use the first external source for resolution
        ExternalId externalSource = externalSources.get(0);

        // check if setup of externalId is required
        if(context.getTesting() && context.getSourceId() != null){
            if (externalSource.getExternalId() == null || externalSource.getExternalId().isEmpty()){
                externalSource.setExternalId ( "implicit-device-" + Utils.createCustomUuid());
            }
            String externalIdValue = externalSource.getExternalId();
            String type = externalSources.get(0).getType();
            var adHocDeviceid = ProcessingResultHelper.createImplicitDevice(
                    new ID(type, externalIdValue),
                    context,
                    log,
                    c8yAgent,
                    configurationRegistry.getObjectMapper()
            );
        }
        if (externalSources.isEmpty()) {
            throw new ProcessingException("External source is empty");
        }


        try {
            var gid = new GId(context.getSourceId());
            // Use C8YAgent to resolve external ID to global ID
            var externalId = c8yAgent.resolveGlobalId2ExternalId(tenant, gid,
                    externalSource.getType(),
                    context.getTesting());
            context.setExternalId(externalSource.getExternalId());

            if (externalId != null) {
                return externalId.getExternalId();
            } else {
                throw new ProcessingException("Could not resolve external ID: " + externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

}
