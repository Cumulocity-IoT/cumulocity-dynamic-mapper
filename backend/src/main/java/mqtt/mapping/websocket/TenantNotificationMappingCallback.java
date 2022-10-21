package mqtt.mapping.websocket;

import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.PayloadProcessor;

import java.net.URI;

public class TenantNotificationMappingCallback implements NotificationCallback {

    private Mapping mapping;
    private PayloadProcessor genericCallback;

    public TenantNotificationMappingCallback(Mapping mapping, PayloadProcessor callback){
        this.mapping = mapping;
        this.genericCallback = callback;
    }

    @Override
    public void onOpen(URI serverUri) {

    }

    @Override
    public void onNotification(Notification notification) {
        genericCallback.publishMessage(mapping,notification.getMessage());
    }

    @Override
    public void onError(Throwable t) {

    }

    @Override
    public void onClose() {
    }
}
