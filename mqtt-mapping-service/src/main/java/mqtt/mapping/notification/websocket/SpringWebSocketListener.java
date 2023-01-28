package mqtt.mapping.notification.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.websocket.Session;

import org.springframework.integration.websocket.WebSocketListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SpringWebSocketListener implements WebSocketListener {

    private final NotificationCallback callback;

    public boolean started;

    private WebSocketSession session = null;

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);


    public SpringWebSocketListener (NotificationCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onMessage(WebSocketSession session, WebSocketMessage<?> message) {
        Object messagePayload = message.getPayload();
        if ( messagePayload instanceof String) {
            String messageString = (String) message.getPayload();
            Notification notification = Notification.parse(messageString);
            this.callback.onNotification(notification);
            if (notification.getAckHeader() != null) {
                CharSequence cs = notification.getAckHeader();
                log.info("Send acklowledgement message" + cs.toString());
                try {
                    WebSocketMessage<String> ack = new TextMessage(cs);
                    session.sendMessage(ack); // ack message
                } catch (Exception e) {
                    log.error("Failed to ack message " + cs.toString(), e);
                }
            } else {
                log.warn("No message id found for ack");
            }

        } else {
            log.warn("Received not text message" + message.toString());
        }
    }

    @Override
    public void afterSessionStarted(WebSocketSession session) {
        this.started = true;
        this.session = session;
        Map attr = session.getAttributes();
        log.info("OnOpen: " + ", attributes: " + attr.keySet());
        URI ur = null;
        try {
            ur = new URI("wss", session.getRemoteAddress().getHostName(), null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        this.callback.onOpen(ur);
        executorService.scheduleAtFixedRate(() -> {
            try {
                session.sendMessage(new PingMessage());
            } catch (IOException e) {
                log.error("Failed to send ping ", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void afterSessionEnded(WebSocketSession session, CloseStatus closeStatus) {
    }

    @Override
    public List<String> getSubProtocols() {
        return Collections.singletonList("v10.stomp");
    }

}
