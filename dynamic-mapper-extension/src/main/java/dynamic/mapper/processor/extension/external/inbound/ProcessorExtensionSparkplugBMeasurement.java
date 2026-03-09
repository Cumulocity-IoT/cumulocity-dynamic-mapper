package dynamic.mapper.processor.extension.external.inbound;

import com.google.protobuf.InvalidProtocolBufferException;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import java.util.ArrayList;
import lombok.Generated;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessorExtensionSparkplugBMeasurement implements ProcessorExtensionInbound<byte[]> {

    @Generated
    private static final Logger log = LoggerFactory.getLogger(ProcessorExtensionSparkplugBMeasurement.class);

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
            ArrayList<CumulocityObject> cumulocityMessages= new ArrayList<>();

            for (SparkplugBProto.Payload.Metric metric : payloadProtobuf.getMetricsList()) {
                //Ignore Null values
                if(metric.getIsNull())
                    continue;
                //For C8Y only numeric values are allowed in measurements, so we validate and convert the metric value accordingly
                Number numberMetric = validateMetricValueForMeasurement(getMetricValue(metric));
                //Name is in most messages not part of payload, but alias is
                String metricName = metric.hasName() ? metric.getName() : String.valueOf(metric.getAlias());
                if(numberMetric != null) {
                    CumulocityObject measurement = CumulocityObject.measurement()
                            .type("SparkplugMetrics")
                            .time(new DateTime(metric.getTimestamp()))
                            //There is no data on series in sparkplug b, so we use the metric name as fragment and a fixed value for series here "T"
                            //There is no unit of measure information in sparkplug b, so we ignore it with null
                            .fragment(metricName, "T", numberMetric, null)
                            .externalId(deviceMetaData.deviceId(), externalIdType)
                            .build();
                    cumulocityMessages.add(measurement);
                } else {
                    //For non-numeric values we create events instead of measurements, so that the data is not lost but can still be used in C8Y
                    CumulocityObject event = CumulocityObject.event()
                            .type("SparkplugMetrics")
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

    /**
     * Parses SparkPlug B topic structure into its component parts.
     * Expected format: {namespace}/{groupId}/{messageType}/{edgeNodeId}/{deviceId}
     * Example: spBv1.0/myGroup/DDATA/myEdgeNode/myDevice
     */
    private DeviceMetaData getDeviceMetaDataOutOfTopic(String sparkplugBTopic) {
        String[] splitTopic = sparkplugBTopic.split("/");
        if (splitTopic.length != 5) {
            throw new IllegalArgumentException("topic does not follow sparkplug b standard");
        }
        // splitTopic[0] = namespace (e.g. "spBv1.0")
        // splitTopic[1] = groupId
        // splitTopic[2] = messageType (e.g. "DDATA")
        // splitTopic[3] = edgeNodeId
        // splitTopic[4] = deviceId
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
    //FIXME That's not a unit but a type which is not useful in C8Y
    private String getMetricUnit(SparkplugBProto.Payload.Metric metric) {
        if (metric.hasBooleanValue()) {
            return "bool";
        } else if (metric.hasDoubleValue()) {
            return "double";
        } else if (metric.hasFloatValue()) {
            return "float";
        } else if (metric.hasIntValue()) {
            return "int";
        } else if (metric.hasLongValue()) {
            return "long";
        } else {
            return "string";
        }
    }

    private record DeviceMetaData(String deviceId, String groupId, String messageType, String edgeNodeId) {
    }
}
