package mqtt.mapping.websocket;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class Notification {
    private final String ackHeader;
    private final List<String> notificationHeaders;
    private final String message;

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
            return new Notification(null, Collections.emptyList(), message);
        }
        return new Notification(headers.get(0), Collections.unmodifiableList(headers.subList(1, headers.size())), message);
    }

}
