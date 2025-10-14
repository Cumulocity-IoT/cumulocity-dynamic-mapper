package dynamic.mapper.processor.flow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Helper class for working with JavaScript union types and arrays in GraalJS
 */
public class JavaScriptInteropHelper {
    
    /**
     * Creates a JavaScript-compatible message object from Java objects
     * @param context The GraalJS context
     * @param message Either DeviceMessage or CumulocityMessage
     * @return Value representing the message in JavaScript
     */
    public static Value createJSMessage(Context context, Object message) {
        return context.getBindings("js").getMember("Object").newInstance(message);
    }
    
    /**
     * Checks if a Value represents a DeviceMessage (by checking for 'topic' property)
     * @param value The value to check
     * @return true if it's a DeviceMessage
     */
    public static boolean isDeviceMessage(Value value) {
        return value.hasMembers() && value.hasMember("topic");
    }
    
    /**
     * Checks if a Value represents a CumulocityMessage (by checking for 'cumulocityType' property)
     * @param value The value to check
     * @return true if it's a CumulocityMessage
     */
    public static boolean isCumulocityMessage(Value value) {
        return value.hasMembers() && value.hasMember("cumulocityType");
    }
    
    /**
     * Converts a JavaScript array result back to Java objects
     * @param arrayValue The JavaScript array returned from onMessage
     * @return Array of converted Java objects
     */
    public static Object[] convertJSArrayToJava(Value arrayValue) {
        if (!arrayValue.hasArrayElements()) {
            throw new IllegalArgumentException("Value is not a JavaScript array");
        }
        
        long arraySize = arrayValue.getArraySize();
        Object[] result = new Object[(int) arraySize];
        
        for (int i = 0; i < arraySize; i++) {
            Value element = arrayValue.getArrayElement(i);
            
            if (isDeviceMessage(element)) {
                result[i] = convertToDeviceMessage(element);
            } else if (isCumulocityMessage(element)) {
                result[i] = convertToCumulocityMessage(element);
            } else {
                result[i] = element; // Keep as Value for unknown types
            }
        }
        
        return result;
    }
    
    public static CumulocityMessage convertToCumulocityMessage(Value value) {
        CumulocityMessage msg = new CumulocityMessage();
        
        // Convert Value to Java Object immediately
        if (value.hasMember("payload")) {
            msg.setPayload(convertValueToJavaObject(value.getMember("payload")));
        }
        if (value.hasMember("cumulocityType")) {
            msg.setCumulocityType(value.getMember("cumulocityType").asString());
        }
        if (value.hasMember("action")) {
            msg.setAction(value.getMember("action").asString());
        }
        if (value.hasMember("externalSource")) {
            msg.setExternalSource(convertValueToJavaObject(value.getMember("externalSource")));
        }
        if (value.hasMember("internalSource")) {
            msg.setInternalSource(convertValueToJavaObject(value.getMember("internalSource")));
        }
        if (value.hasMember("destination")) {
            msg.setDestination(value.getMember("destination").asString());
        }
        
        return msg;
    }

    public static DeviceMessage convertToDeviceMessage(Value value) {
        DeviceMessage msg = new DeviceMessage();
        
        if (value.hasMember("payload")) {
            msg.setPayload(convertValueToJavaObject(value.getMember("payload")));
        }
        if (value.hasMember("topic")) {
            msg.setTopic(value.getMember("topic").asString());
        }
        if (value.hasMember("transportId")) {
            msg.setTransportId(value.getMember("transportId").asString());
        }
        if (value.hasMember("clientId")) {
            msg.setClientId(value.getMember("clientId").asString());
        }
        
        // Handle transportFields map
        if (value.hasMember("transportFields") && value.getMember("transportFields").hasMembers()) {
            Map<String, String> transportFields = new HashMap<>();
            Value transportFieldsValue = value.getMember("transportFields");
            for (String key : transportFieldsValue.getMemberKeys()) {
                transportFields.put(key, transportFieldsValue.getMember(key).asString());
            }
            msg.setTransportFields(transportFields);
        }
        
        // Handle time
        if (value.hasMember("time") && !value.getMember("time").isNull()) {
            // Convert JS Date to Java Instant
            Value timeValue = value.getMember("time");
            if (timeValue.isDate()) {
                // GraalJS returns LocalDate, convert to Instant
                LocalDate localDate = timeValue.asDate();
                msg.setTime(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
            } else if (timeValue.isString()) {
                // Handle string dates (ISO format)
                try {
                    msg.setTime(Instant.parse(timeValue.asString()));
                } catch (Exception e) {
                    // If parsing fails, use current time as fallback
                    msg.setTime(Instant.now());
                }
            } else if (timeValue.isNumber()) {
                // Handle timestamp in milliseconds
                long timestamp = timeValue.asLong();
                msg.setTime(Instant.ofEpochMilli(timestamp));
            }
        }
        
        return msg;
    }

    /**
     * Recursively converts GraalJS Value to Java objects
     */
    public static Object convertValueToJavaObject(Value value) {
        if (value.isNull()) {
            return null;
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        } else if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.isDate()) {
            // Handle LocalDate properly
            LocalDate localDate = value.asDate();
            return localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (value.hasArrayElements()) {
            // Convert to Java List
            List<Object> list = new ArrayList<>();
            long size = value.getArraySize();
            for (int i = 0; i < size; i++) {
                list.add(convertValueToJavaObject(value.getArrayElement(i)));
            }
            return list;
        } else if (value.hasMembers()) {
            // Convert to Java Map
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValueToJavaObject(value.getMember(key)));
            }
            return map;
        } else if (value.hasBufferElements()) {
            // Handle ArrayBuffer/ByteBuffer
            byte[] bytes = new byte[(int) value.getBufferSize()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = value.readBufferByte(i);
            }
            return bytes;
        } else {
            // Fallback - convert to string representation
            return value.toString();
        }
    }
    
    /**
     * Helper method to convert JavaScript Date objects to Instant
     * Handles multiple date formats that might come from JavaScript
     */
    public static Instant convertJavaScriptDateToInstant(Value dateValue) {
        if (dateValue.isNull()) {
            return null;
        }
        
        if (dateValue.isDate()) {
            // GraalJS Date objects
            LocalDate localDate = dateValue.asDate();
            return localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (dateValue.isString()) {
            // Handle ISO string dates
            try {
                return Instant.parse(dateValue.asString());
            } catch (Exception e) {
                // If parsing fails, return current time
                return Instant.now();
            }
        } else if (dateValue.isNumber()) {
            // Handle timestamp in milliseconds (JavaScript Date.getTime())
            long timestamp = dateValue.asLong();
            return Instant.ofEpochMilli(timestamp);
        } else {
            // Fallback to current time
            return Instant.now();
        }
    }
}