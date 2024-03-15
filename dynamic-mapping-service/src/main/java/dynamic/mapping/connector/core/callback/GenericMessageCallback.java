package dynamic.mapping.connector.core.callback;

public interface GenericMessageCallback {
    void onClose(String closeMessage, Throwable closeException);

    void onMessage(ConnectorMessage message) throws Exception;

    void onError( Throwable errorException);
}
