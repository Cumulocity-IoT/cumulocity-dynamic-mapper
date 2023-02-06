package mqtt.mapping.notification.websocket;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mqtt.mapping.model.API;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class Notification {
    private final String ackHeader;
    private final List<String> notificationHeaders;
    private final String message;
    private final API api;

    public static Notification parse(String message) {
        ArrayList<String> headers = new ArrayList<>(8);
        while (true) {
            int i = message.indexOf('\n');
            if (i == -1) {
                break;
            }
            String header = message.substring(0, i);
            message = message.substring(i + 1);
            if (header.length() == 0) {
                break;
            }
            headers.add(header);
        }
        if (headers.isEmpty()) {
            return new Notification(null, Collections.emptyList(), message, API.EMPTY);
        }
        String apiString = headers.get(1).split("/")[1];
        API api = API.EMPTY;
        switch (apiString) {
            case "alarms":
                api = API.ALARM;
                break;
            case "events":
                api = API.EVENT;
                break;
            case "measurements":
                api = API.MEASUREMENT;
                break;
            case "managedObjects":
                api = API.INVENTORY;
                break;
            case "operations":
                api = API.OPERATION;
                break;
            default:
                break;
        }
        return new Notification(headers.get(0), Collections.unmodifiableList(headers.subList(1, headers.size())), message, api);
    }

}
