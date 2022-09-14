/* package mqttagent.websocket;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {
	
	List<WebSocketSession> sessions = new CopyOnWriteArrayList<WebSocketSession>();
    @Autowired
    ObjectMapper objectMapper;

	public void sendMessage(WebSocketSession session, StatusMessage message) {
		
        String payload;
        try {
            log.info("New message to send to client {}, {}", message.count, session);
            payload = objectMapper.writeValueAsString(message);
            TextMessage tm = new TextMessage(payload);
            session.sendMessage(tm);
        } catch (IOException e) {
            log.error("Could not handle|send message {}, ", e, message);
            e.printStackTrace();
        }
	}

    @Override
	public void handleTextMessage(WebSocketSession session, TextMessage message)
			throws InterruptedException, IOException {
            log.info("New message to received from client {}", message.getPayload());
	}

	public void send(StatusMessage message)
			throws InterruptedException, IOException {
                log.info("New message to send to client {}", message.count);
        sessions.forEach(session -> {
			sendMessage(session, message);
        });
	}
	
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Connected new session {}, ", session);
		sessions.add(session);
	}
} */