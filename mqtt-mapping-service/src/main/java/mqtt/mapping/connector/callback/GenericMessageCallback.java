package mqtt.mapping.connector.callback;

public interface GenericMessageCallback {
    void onClose(String closeMessage, Throwable closeException);

    void onMessage(String topic, ConnectorMessage message) throws Exception;

    void onError( Throwable errorException);
}
