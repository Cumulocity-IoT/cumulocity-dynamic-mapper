package dynamic.mapping.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
public class CustomWebSocketClient extends WebSocketClient {
    private final NotificationCallback callback;
    private ScheduledExecutorService executorService = null;
    private String tenant;

    public CustomWebSocketClient(URI serverUri, NotificationCallback callback, String tenant) {
        super(serverUri);
        this.callback = callback;
        this.tenant = tenant;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        this.executorService = Executors.newScheduledThreadPool(1);
        this.callback.onOpen(this.uri);
        //send(ByteBuffer.allocate(0));
        executorService.scheduleAtFixedRate(this::sendPing, 1, 1, TimeUnit.MINUTES);
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
        log.info("Tenant {} - WebSocket closed {} statusCode: {}, reason: {}", tenant, remote ? "by server." : "", statusCode, reason);
        if (this.executorService != null)
            this.executorService.shutdownNow();
        this.callback.onClose(statusCode, reason);
    }

    @Override
    public void onError(Exception e) {
        log.error("Tenant {} - WebSocket error: ", tenant, e);
        this.callback.onError(e);
    }
}
