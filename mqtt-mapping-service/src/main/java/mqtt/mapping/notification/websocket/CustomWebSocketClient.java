package mqtt.mapping.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
public class CustomWebSocketClient extends WebSocketClient {

    private final NotificationCallback callback;

    private boolean started;

    private int retryCount = 0;

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);


    public CustomWebSocketClient(URI serverUri, NotificationCallback callback) {
        super(serverUri);
        this.callback = callback;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        this.callback.onOpen(this.uri);
        this.started = true;
        this.retryCount = 0;
        executorService.scheduleAtFixedRate(() -> {
            send(ByteBuffer.allocate(0));
        }, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void onMessage(String message) {
        Notification notification = Notification.parse(message);
        this.callback.onNotification(notification);
        if (notification.getAckHeader() != null) {
            send(notification.getAckHeader()); // ack message
        } else {
            throw new RuntimeException("No message id found for ack");
        }
    }

    @Override
    public void onClose(int statusCode, String reason, boolean remote) {
        log.info("WebSocket closed " + (remote ? "by server. " : "") + " Code:" + statusCode + ", reason: " + reason);
        this.callback.onClose(statusCode, reason);

    }

    @Override
    public void onError(Exception e) {
        log.error("WebSocket error:" + e);
        this.callback.onError(e);
    }
}
