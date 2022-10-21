package mqtt.mapping.websocket;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Data
public class Properties {


    @Value("${agent.websocket.url}")
    private String webSocketBaseUrl;

    @Value("${agent.subscriber}")
    private String subscriber;

    @Value("${agent.websocket.library:@null}")
    private String webSocketLibrary;

}
