package mqttagent.websocket;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.TextMessage;

import lombok.extern.slf4j.Slf4j;

 
@Slf4j
@Controller
public class MQTTStatusWebsocketController {
 
    /* 
    @GetMapping("/stomp-broadcast")
    public String getWebSocketBroadcast() {
        return "stomp-broadcast";
    }
    */
    @MessageMapping("/command")
    public void onMessage(TextMessage msg) throws Exception {
        log.info("Received msg: {}", msg.getPayload());
    }
    

    @SendTo("/topic/monitor")
    public StatusMessage send(StatusMessage statusMessage) throws Exception {
        return  statusMessage;
    } 
}
