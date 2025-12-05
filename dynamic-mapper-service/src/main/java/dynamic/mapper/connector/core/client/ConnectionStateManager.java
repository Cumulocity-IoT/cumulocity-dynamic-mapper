/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.connector.core.client;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.ConnectorStatusEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages connection state and status transitions for a connector.
 * Thread-safe state management with lifecycle callbacks.
 */
@Slf4j
public class ConnectionStateManager {
    
    private final String tenant;
    private final String connectorName;
    private final String connectorIdentifier;
    private final MutableBoolean connectionState = new MutableBoolean(false);
    
    @Getter
    private final AtomicReference<ConnectorStatusEvent> connectorStatus;
    
    private ConnectorStatus previousStatus = ConnectorStatus.UNKNOWN;
    
    private final Consumer<ConnectorStatusEvent> statusChangeCallback;

    private ConnectorRegistry connectorRegistry;
    
    public ConnectionStateManager(String tenant, 
                                 String connectorName,
                                 String connectorIdentifier,
                                 Consumer<ConnectorStatusEvent> statusChangeCallback,
                                 ConnectorRegistry connectorRegistry) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.connectorIdentifier = connectorIdentifier;
        this.statusChangeCallback = statusChangeCallback;
        this.connectorRegistry = connectorRegistry;
        this.connectorStatus = new AtomicReference<>(
            ConnectorStatusEvent.unknown(connectorName, connectorIdentifier));
    }
    
    public boolean isConnected() {
        return connectionState.booleanValue();
    }
    
    public void setConnected(boolean connected) {
        boolean wasConnected = connectionState.booleanValue();
        connectionState.setValue(connected);
        
        if (wasConnected != connected) {
            log.info("{} - Connection state changed: {} -> {} for connector: {}", 
                    tenant, wasConnected, connected, connectorName);
            
            if (connected) {
                updateStatus(ConnectorStatus.CONNECTED, true, true);
            } else {
                updateStatus(ConnectorStatus.DISCONNECTED, true, true);
            }
        }
    }
    
    public void updateStatus(ConnectorStatus status, boolean clearMessage, boolean sendEvent) {
        ConnectorStatusEvent currentStatus = connectorStatus.get();
        currentStatus.updateStatus(status, clearMessage);
        connectorRegistry.getConnectorStatusMap(tenant).put(connectorIdentifier, currentStatus);
        
        if (sendEvent && !status.equals(previousStatus)) {
            previousStatus = status;
            notifyStatusChange(currentStatus);
        }
    }
    
    public void updateStatusWithError(Exception e) {
        ConnectorStatusEvent currentStatus = connectorStatus.get();
        String errorMessage = buildErrorMessage(e);
        currentStatus.setMessage(errorMessage);
        currentStatus.updateStatus(ConnectorStatus.FAILED, false);
        
        notifyStatusChange(currentStatus);
    }
    
    private String buildErrorMessage(Exception e) {
        StringBuilder messageBuilder = new StringBuilder()
                .append(" --- ")
                .append(e.getClass().getName())
                .append(": ")
                .append(e.getMessage());
        
        Optional.ofNullable(e.getCause()).ifPresent(cause ->
                messageBuilder.append(" --- Caused by ")
                        .append(cause.getClass().getName())
                        .append(": ")
                        .append(cause.getMessage()));
        
        return messageBuilder.toString();
    }
    
    private void notifyStatusChange(ConnectorStatusEvent status) {
        if (statusChangeCallback != null) {
            try {
                statusChangeCallback.accept(status);
            } catch (Exception e) {
                log.error("{} - Error in status change callback: {}", tenant, e.getMessage(), e);
            }
        }
    }
    
    public ConnectorStatus getCurrentStatus() {
        return connectorStatus.get().getStatus();
    }
    
    public String getCurrentMessage() {
        return connectorStatus.get().getMessage();
    }
}
