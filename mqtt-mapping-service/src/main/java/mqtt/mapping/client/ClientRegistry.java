package mqtt.mapping.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
@Slf4j
public class ClientRegistry {

    protected HashMap<String, ConnectorClient> clientMap = new HashMap<>();
    public void registerClient(String connectorId, ConnectorClient client) {
        if(clientMap.get(connectorId) == null) {
            log.info("Adding new client with id {}...", connectorId);
            clientMap.put(connectorId, client);
        } else {
            log.info("Client {} is already registered!", connectorId);
        }
    }

    public ConnectorClient getClient(String connectorId) {
        if(clientMap.get(connectorId) != null) {
            return clientMap.get(connectorId);
        } else {
            log.info("Client {} is not registered", connectorId);
            return null;
        }
    }

    public HashMap<String, ConnectorClient> getAllClients() {
        return clientMap;
    }

    public void unregisterClient(String connectorId) {
        if(clientMap.get(connectorId) != null) {
            clientMap.remove(connectorId);
        } else {
            log.info("Client {} is not registered", connectorId);
        }
    }
}
