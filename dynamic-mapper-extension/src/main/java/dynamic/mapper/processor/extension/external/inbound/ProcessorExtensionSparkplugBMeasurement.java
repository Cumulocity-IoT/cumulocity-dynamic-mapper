package dynamic.mapper.processor.extension.external.inbound;

import com.google.protobuf.InvalidProtocolBufferException;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import java.util.ArrayList;
import java.util.Map;
import lombok.Generated;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sparkplug B measurement processor that reads fragment name and unit from
 * the extension configuration supplied via the mapping UI.
 *
 * <p>Expected parameter keys (under the top-level {@code parameter} map):
 * <ul>
 *   <li>{@code units.unit1} – unit string for numeric metrics (e.g. "V")</li>
 *   <li>{@code fragment} – fragment / type name used in the C8Y measurement (e.g. "Energy")</li>
 * </ul>
 *
 * <p>Example mapping parameter YAML:
 * <pre>
 * units:
 *   unit1: V
 *   unit2: A
 * fragment: Energy
 * </pre>
 */
public class ProcessorExtensionSparkplugBMeasurement implements ProcessorExtensionInbound<byte[]> {

    @Generated
    private static final Logger log = LoggerFactory.getLogger(ProcessorExtensionSparkplugBMeasurement.class);

    private static final String DEFAULT_FRAGMENT = "SparkplugMetrics";
    private static final String CONFIG_KEY_UNITS = "units";
    private static final String CONFIG_KEY_UNIT1 = "unit1";
    private static final String CONFIG_KEY_FRAGMENT = "fragment";

    public ProcessorExtensionSparkplugBMeasurement() {
    }

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            byte[] payload = message.getPayload();
            if (payload == null) {
                String errorMsg = "Protobuf message payload is null";
                log.warn("{} - {}", context.getTenant(), errorMsg);
                context.addWarning(errorMsg);
                return new CumulocityObject[0];
            }

            // Read parameter values supplied from the mapping UI (under the "parameter" key)
            Map<String, Object> config = context.getConfigAsMap();
            String unit1 = DEFAULT_FRAGMENT;
            String fragment = DEFAULT_FRAGMENT;

            @SuppressWarnings("unchecked")
            Map<String, Object> parameter = (Map<String, Object>) config.get("parameter");
            if (parameter != null) {
                log.info("{} - Extension parameter defined: {}", context.getTenant(), parameter);
                @SuppressWarnings("unchecked")
                Map<String, Object> units = (Map<String, Object>) parameter.get(CONFIG_KEY_UNITS);
                if (units != null) {
                    if (units.get(CONFIG_KEY_UNIT1) instanceof String u) {
                        unit1 = u;
                    }
                } else {
                    log.debug("{} - No 'units' parameter found, using defaults", context.getTenant());
                }
                if (parameter.get(CONFIG_KEY_FRAGMENT) instanceof String f) {
                    fragment = f;
                }
            } else {
                log.debug("{} - No 'parameter' map found, using defaults", context.getTenant());
            }

            log.debug("{} - Parsing protobuf message, payload size: {} bytes",
                    context.getTenant(), payload.length);

            SparkplugBProto.Payload payloadProtobuf = SparkplugBProto.Payload.parseFrom(payload);
            DeviceMetaData deviceMetaData = getDeviceMetaDataOutOfTopic(message.getTopic());

            if (!"DDATA".equals(deviceMetaData.messageType())) {
                throw new IllegalArgumentException(
                        "expected DDATA message type of sparkplug b message, but it was %s"
                                .formatted(deviceMetaData.messageType()));
            }

            log.info("{} - Processing new measurement: topic={}, metrics={}, deviceId={}, time={}",
                    context.getTenant(),
                    message.getTopic(),
                    payloadProtobuf.getMetricsList(),
                    deviceMetaData.deviceId(),
                    new DateTime(payloadProtobuf.getTimestamp()));

            String externalIdType = context.getMapping().getExternalIdType();
            ArrayList<CumulocityObject> cumulocityMessages = new ArrayList<>();

            for (SparkplugBProto.Payload.Metric metric : payloadProtobuf.getMetricsList()) {
                if (metric.getIsNull())
                    continue;

                Number numberMetric = validateMetricValueForMeasurement(getMetricValue(metric));
                String metricName = metric.hasName() ? metric.getName() : String.valueOf(metric.getAlias());

                if (numberMetric != null) {
                    CumulocityObject measurement = CumulocityObject.measurement()
                            .type(fragment)
                            .time(new DateTime(metric.getTimestamp()))
                            .fragment(fragment, metricName, numberMetric, unit1)
                            .externalId(deviceMetaData.deviceId(), externalIdType)
                            .build();
                    cumulocityMessages.add(measurement);
                } else {
                    CumulocityObject event = CumulocityObject.event()
                            .type(fragment)
                            .time(new DateTime(metric.getTimestamp()))
                            .text("Received non-numeric metric value for metric %s, value: %s".formatted(metricName, getMetricValue(metric)))
                            .property(metricName, getMetricValue(metric))
                            .externalId(deviceMetaData.deviceId(), externalIdType)
                            .build();
                    cumulocityMessages.add(event);
                }
            }

            return cumulocityMessages.toArray(new CumulocityObject[0]);

        } catch (InvalidProtocolBufferException e) {
            String errorMsg = "Failed to parse protobuf event: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        } catch (Exception e) {
            String errorMsg = "Failed to process custom event: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }

    private DeviceMetaData getDeviceMetaDataOutOfTopic(String sparkplugBTopic) {
        String[] splitTopic = sparkplugBTopic.split("/");
        if (splitTopic.length != 5) {
            throw new IllegalArgumentException("topic does not follow sparkplug b standard");
        }
        return new DeviceMetaData(splitTopic[4], splitTopic[1], splitTopic[2], splitTopic[3]);
    }

    private Number validateMetricValueForMeasurement(Object metricValue) {
        if (metricValue instanceof Number) {
            return ((Number) metricValue).doubleValue();
        } else {
            return null;
        }
    }

    private Object getMetricValue(SparkplugBProto.Payload.Metric metric) {
        if (metric.hasBooleanValue()) {
            return metric.getBooleanValue();
        } else if (metric.hasDoubleValue()) {
            return metric.getDoubleValue();
        } else if (metric.hasFloatValue()) {
            return metric.getFloatValue();
        } else if (metric.hasIntValue()) {
            return metric.getIntValue();
        } else if (metric.hasLongValue()) {
            return metric.getLongValue();
        } else {
            return metric.getStringValue();
        }
    }

    private record DeviceMetaData(String deviceId, String groupId, String messageType, String edgeNodeId) {
    }
}
