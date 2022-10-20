package mqtt.mapping.websocket;

public class ActiveSocketClientVo {

    WebSocketClientInterface socketClient;

    String subscriptionId;

    String socketToken;

    String tenant;

    public ActiveSocketClientVo(String tenant,String subscriptionId,String socketToken){
        this.subscriptionId = subscriptionId;
        this.socketToken = socketToken;
        this.tenant = tenant;
    }

    public ActiveSocketClientVo(WebSocketClientInterface socketClient,
                                String subscriptionId){
        this.socketClient = socketClient;
        this.subscriptionId = subscriptionId;
    }

    public WebSocketClientInterface getSocketClient() {
        return socketClient;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSocketClient(WebSocketClientInterface socketClient) {
        this.socketClient = socketClient;
    }

    public String getSocketToken() {
        return socketToken;
    }

    public String getTenant() {
        return tenant;
    }
}
