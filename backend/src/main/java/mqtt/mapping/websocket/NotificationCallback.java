package mqtt.mapping.websocket;

import java.net.URI;

/**
 * Implement this interface to handle notifications.
 */
public interface NotificationCallback {

    /**
     * Called when a connection to the WebSocket server is successfully established.
     * @param serverUri the WebSocket URI that was successfully connected to.
     */
    void onOpen(URI serverUri);

    /**
     * Called on receiving a notification. The notification will be acknowledged if no exception raised.
     * @param notification the notification received.
     */
    void onNotification(Notification notification);

    /**
     * Called on receiving an exception from the WebSocket connection. This may be whilst actively connected or during connection/disconnection.
     * @param t the exception thrown from the connection.
     */
    void onError(Throwable t);

    /**
     * Called on close of the underlying WebSocket connection. Normally, a reconnection should be attempted.
     */
    void onClose();
}
