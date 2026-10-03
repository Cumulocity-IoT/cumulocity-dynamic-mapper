package dynamic.mapper.processor;

import java.util.List;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.springframework.beans.factory.annotation.Autowired;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.mapping.resolver.InventoryFilterEvaluator;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class CommonProcessor {

    @Autowired
    private ServiceRegistry serviceRegistry;

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private InventoryFilterEvaluator inventoryFilterEvaluator;

    /**
     * Evaluates an inventory filter against cached inventory data
     */
    protected boolean evaluateInventoryFilter(String tenant, String filterExpression, String sourceId,
            Boolean testing) {
        return inventoryFilterEvaluator.evaluate(tenant, filterExpression, sourceId, Boolean.TRUE.equals(testing));
    }

    /**
     * Describes where in the user's JavaScript an exception happened, as {@code " (line L, column C)"},
     * or an empty string when there is no JavaScript location to report.
     *
     * <p>Only a location in guest (JS) code is reported. The old implementation fell back to the line
     * of the first <em>Java</em> stack frame for any other exception, so a failure on the Java side
     * was presented as "line 214" of a script that has no such line.
     *
     * <p>A syntax error carries its location on the exception itself (it has no stack); a runtime
     * error carries it on the first guest frame. Both are mapped back to the author's line: in
     * flat-script mode the mapping code is wrapped in {@code (function() {} + newline, which pushes
     * every line down by one, so that is taken off again — otherwise every reported line would be
     * off by one against what the editor shows.
     */
    protected static String describeJsLocation(Throwable e) {
        PolyglotException pe = findPolyglotException(e);
        if (pe == null) {
            return "";
        }
        SourceSection loc = pe.isSyntaxError() ? pe.getSourceLocation() : null;
        if (loc == null) {
            for (PolyglotException.StackFrame frame : pe.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                    loc = frame.getSourceLocation();
                    break;
                }
            }
        }
        if (loc == null || !loc.isAvailable()) {
            return "";
        }
        int line = loc.getStartLine();
        String sourceName = loc.getSource().getName();
        if (sourceName != null && sourceName.startsWith(Mapping.SMART_FUNCTION_NAME + "_")
                && sourceName.endsWith(".js") && line > 1) {
            line--;
        }
        return String.format(" (line %d, column %d)", line, loc.getStartColumn());
    }

    /** First {@link PolyglotException} in the cause chain, or {@code null}. */
    protected static PolyglotException findPolyglotException(Throwable e) {
        for (int depth = 0; e != null && depth < 10; depth++, e = e.getCause()) {
            if (e instanceof PolyglotException pe) {
                return pe;
            }
        }
        return null;
    }

    /**
     * A one-line cause for an exception that wraps the real problem: the JavaScript error with its
     * location when there is one in the chain, otherwise the root cause's message. Loading a Smart
     * Function fails inside {@code GraalVMContextService} with a generic "Failed to create pooled
     * GraalVM context" wrapper; reporting only that hid the actual SyntaxError (and its line) from
     * the mapping's author.
     */
    protected static String describeCause(Throwable e) {
        PolyglotException pe = findPolyglotException(e);
        if (pe != null) {
            return pe.getMessage() + describeJsLocation(pe);
        }
        Throwable root = e;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 10; depth++) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
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

        if (externalSourceObj == null || externalSources.isEmpty()) {
            throw new ProcessingException(
                    "External source is empty, cannot resolve device identifier. Define externalSource in the message or use a generic device identifier in the mapping.");
        }

        // Use the first external source for resolution
        ExternalId externalSource = externalSources.get(0);

        if (externalSource.getExternalId() == null || externalSource.getExternalId().isEmpty()) {
            throw new ProcessingException(
                    "External source has no externalId, cannot resolve device identifier. "
                            + "Define both type and externalId in the message.");
        }

        try {
            // Use C8YAgent to resolve external ID to global ID
            var globalId = c8yAgent.resolveExternalId2GlobalId(tenant,
                    new ID(externalSource.getType(), externalSource.getExternalId()),
                    context.isTesting());
            context.setExternalId(externalSource.getExternalId());

            if (globalId != null) {
                return globalId.getManagedObject().getId().getValue();
            } else {
                return null;
                // throw new ProcessingException("Could not resolve external ID: " +
                // externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

    protected String resolveGlobalId2ExternalId(DeviceMessage deviceMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalId> externalSources = JavaScriptInteropHelper
                .convertToExternalIdList(deviceMessage.getExternalSource());

        if (externalSources == null || externalSources.isEmpty()) {
            String mappingExternalIdType = context.getMapping().getExternalIdType();
            if (mappingExternalIdType != null && !mappingExternalIdType.isEmpty()) {
                log.debug("{} - No externalSource in DeviceMessage, falling back to mapping externalIdType: {}",
                        tenant, mappingExternalIdType);
                externalSources = List.of(new ExternalId(null, mappingExternalIdType));
            } else {
                throw new ProcessingException(
                        "External source is empty, cannot resolve device identifier. Define externalSource in the message or use a generic device identifier in the mapping.");
            }
        }
        // Use the first external source for resolution
        ExternalId externalSource = externalSources.get(0);

        // check if setup of externalId is required
        if (context.isTesting() && context.getSourceId() != null) {
            if (externalSource.getExternalId() == null || externalSource.getExternalId().isEmpty()) {
                externalSource.setExternalId("implicit-device-" + Utils.createCustomUuid());
            }
            String externalIdValue = externalSource.getExternalId();
            String type = externalSources.get(0).getType();
            ProcessingResultHelper.createImplicitDevice(
                    new ID(type, externalIdValue),
                    context,
                    log,
                    c8yAgent,
                    serviceRegistry.getObjectMapper());
        }

        try {
            var gid = new GId(context.getSourceId());
            // Use C8YAgent to resolve external ID to global ID
            var externalId = c8yAgent.resolveGlobalId2ExternalId(tenant, gid,
                    externalSource.getType(),
                    context.isTesting());
            context.setExternalId(externalSource.getExternalId());

            if (externalId != null) {
                return externalId.getExternalId();
            } else {
                return null;
                // throw new ProcessingException("Could not resolve external ID: " +
                // externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

}
