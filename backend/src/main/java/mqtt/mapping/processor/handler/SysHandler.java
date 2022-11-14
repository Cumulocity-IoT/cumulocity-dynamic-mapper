package mqtt.mapping.processor.handler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.measurement.MeasurementValue;

import mqtt.mapping.core.C8YAgent;

@Service
public class SysHandler {

    private final String BYTES_RECEIVED = "$SYS/broker/load/bytes/received";

    private final String BYTES_SENT = "$SYS/broker/load/bytes/sent";

    private final String CLIENTS_CONNECTED = "$SYS/broker/clients/connected";

    private final String CLIENTS_PERSISTED = "$SYS/broker/clients/disconnected";

    private final String CLIENTS_MAX = "$SYS/broker/clients/maximum";

    private final String CLIENTS_TOTAL = "$SYS/broker/clients/total";

    private final String MSG_RECEIVED = "$SYS/broker/messages/received";

    private final String MSG_SENT = "$SYS/broker/messages/sent";

    private final String MSG_DROPPED = "$SYS/broker/messages/publish/dropped";

    private final String SUB_COUNT = "$SYS/broker/subscriptions/count";

    @Autowired
    private C8YAgent c8yAgent;

    public void handleSysPayload(String topic, MqttMessage mqttMessage) {
        if (topic == null)
            return;
        byte[] payload = mqttMessage.getPayload();
        HashMap<String, MeasurementValue> mvMap = new HashMap<>();
        if (BYTES_RECEIVED.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("bytes");
            mvMap.put("BytesReceived", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }
        if (BYTES_SENT.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("bytes");
            mvMap.put("BytesSent", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);

        }
        if (CLIENTS_CONNECTED.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("ClientsConnected", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);

        }
        if (CLIENTS_PERSISTED.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("ClientsPersisted", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }
        if (CLIENTS_MAX.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("ClientsMax", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }

        if (CLIENTS_TOTAL.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("ClientsTotal", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }

        if (MSG_RECEIVED.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("MsgReceived", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }

        if (MSG_SENT.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("MsgSent", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }

        if (MSG_DROPPED.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("MsgDropped", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);

        }

        if (SUB_COUNT.equals(topic)) {
            MeasurementValue mv = new MeasurementValue();
            mv.setValue(bytesToBigDecimal(payload));
            mv.setUnit("#");
            mvMap.put("SubCount", mv);
            c8yAgent.createMeasurement("MqttSysStatistics", "mqtt_sysstatistics", c8yAgent.getAgentMOR(),
                    DateTime.now(), mvMap);
        }
    }

    public static BigDecimal bytesToBigDecimal(byte[] bytes) {
        String string = bytesToString(bytes);
        return new BigDecimal(string).setScale(0, RoundingMode.HALF_UP);
    }

    public static String bytesToString(byte[] bytes) {
        return bytesToString(bytes, 0, bytes.length);
    }

    public static String bytesToString(byte[] buffer, int index, int length) {
        return new String(buffer, index, length);
    }
}
