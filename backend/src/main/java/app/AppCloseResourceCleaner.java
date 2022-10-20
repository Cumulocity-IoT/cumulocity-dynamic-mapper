package app;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.service.MQTTClient;
import mqtt.mapping.websocket.TenantNotificationService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

@Component
@Slf4j
public class AppCloseResourceCleaner  {

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8yAgent c8yAgent;

    @Autowired
    TenantNotificationService tenantNotificationService;

    @PreDestroy
    public void destroy() throws Exception {
        log.info("closing mqtt client");
        mqttClient.disconnect();
        if (StringUtils.isNotBlank(c8yAgent.tenant)) {
            log.info("closing websocket");
            tenantNotificationService.closeAllNotUserNotification(c8yAgent.tenant);
        }
    }
}
