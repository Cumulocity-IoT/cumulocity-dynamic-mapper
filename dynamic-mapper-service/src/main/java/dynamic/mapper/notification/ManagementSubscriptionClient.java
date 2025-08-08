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

package dynamic.mapper.notification;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceCollectionRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.C8YMessage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class ManagementSubscriptionClient implements NotificationCallback {

    public static String CONNECTOR_NAME = "MANAGEMENT_SUBSCRIPTION_CONNECTOR";
    public static String CONNECTOR_ID = "MANAGEMENT_SUBSCRIPTION_ID";

    private C8YNotificationSubscriber notificationSubscriber;

    private String tenant;

    private ExecutorService virtualThreadPool;

    private ConfigurationRegistry configurationRegistry;

    private Map<String, ManagedObjectRepresentation> groupCache = new ConcurrentHashMap<>();

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public ManagementSubscriptionClient(ConfigurationRegistry configurationRegistry,
            String tenant) {
        this.tenant = tenant;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, managing subscriptions",
                tenant);
        notificationSubscriber.setDeviceConnectionStatus(tenant, 200);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());

        // check for update of managedObject -> updateSubscriptionByChangesInDeviceGroup
        if ("UPDATE".equals(notification.getOperation())) {
            C8YMessage c8yMessage = new C8YMessage();
            Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
            c8yMessage.setParsedPayload(parsedPayload);
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setOperation(notification.getOperation());
            String messageId = String.valueOf(parsedPayload.get("id"));
            c8yMessage.setMessageId(messageId);
            try {
                var expression = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expression.evaluate(parsedPayload);
                String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
                c8yMessage.setSourceId(sourceId);
            } catch (Exception e) {
                log.warn("Could not extract source.id: {}", e.getMessage());

            }
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            // TODO Return a future so it can be blocked for QoS 1 or 2
            return updateSubscriptionByChangesInDeviceGroup(c8yMessage);
        } else if ("CREATE".equals(notification.getOperation())) {
        // check for creation of managedObject -> updateSubscriptionByChangesInDeviceTypes

            C8YMessage c8yMessage = new C8YMessage();
            Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
            c8yMessage.setParsedPayload(parsedPayload);
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setOperation(notification.getOperation());
            String messageId = String.valueOf(parsedPayload.get("id"));
            c8yMessage.setMessageId(messageId);
            try {
                var expression = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expression.evaluate(parsedPayload);
                String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
                c8yMessage.setSourceId(sourceId);
            } catch (Exception e) {
                log.warn("Could not extract source.id: {}", e.getMessage());

            }
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            // TODO Return a future so it can be blocked for QoS 1 or 2
            return updateSubscriptionByChangesInDeviceTypes(c8yMessage);
        }
        return result;
    }

    private ProcessingResult<?> updateSubscriptionByChangesInDeviceGroup(C8YMessage c8yMessage) {
        log.debug("{} - Update DeviceGroup: {}", tenant, c8yMessage.toString());
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();
        Future<?> futureProcessingResult = virtualThreadPool.submit(
                new UpdateSubscriptionDeviceGroupTask(configurationRegistry,
                        c8yMessage, groupCache));
        return result;
    }

    private ProcessingResult<?> updateSubscriptionByChangesInDeviceTypes(C8YMessage c8yMessage) {
        log.debug("{} - Update DeviceGroup: {}", tenant, c8yMessage.toString());
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();
        Future<?> futureProcessingResult = virtualThreadPool.submit(
                new UpdateSubscriptionDeviceTypeTask(configurationRegistry,
                        c8yMessage, groupCache));
        return result;
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", tenant, t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", tenant);
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(tenant, 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(tenant, null);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

    public static class UpdateSubscriptionDeviceGroupTask<T>
            implements Callable<List<Future<NotificationSubscriptionRepresentation>>> {
        C8YMessage c8yMessage;
        ConfigurationRegistry configurationRegistry;
        C8YNotificationSubscriber notificationSubscriber;
        Map<String, ManagedObjectRepresentation> groupCache;

        public UpdateSubscriptionDeviceGroupTask(ConfigurationRegistry configurationRegistry,
                C8YMessage c8yMessage, Map<String, ManagedObjectRepresentation> groupCache) {
            this.c8yMessage = c8yMessage;
            this.configurationRegistry = configurationRegistry;
            this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
            this.groupCache = groupCache;
        }

        @Override
        public List<Future<NotificationSubscriptionRepresentation>> call() throws Exception {
            List<Future<NotificationSubscriptionRepresentation>> processingResult = new ArrayList<>();
            String tenant = c8yMessage.getTenant();

            // get the group from groupCache as cachedGroup
            // if not found report warning
            // if found compare childAssets.references in cachedGroup with the
            // payload.childAssets.references

            // "childAssets": {
            // "references": [
            // {
            // "managedObject": {
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/9877263",
            // "id": "9877263"
            // },
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6673218/childAssets/9877263"
            // },
            // {
            // "managedObject": {
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/5677264",
            // "id": "5677264"
            // },
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6673218/childAssets/5677264"
            // },
            // {
            // "managedObject": {
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/4827613",
            // "id": "4827613"
            // },
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6673218/childAssets/4827613"
            // }
            // ],
            // "self":
            // "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6673218/childAssets"
            // }

            // for each childAsset in payload.childAssets.references that is not in
            // childAssets.references of cachedGroup call
            // notificationSubscriber.subscribeDeviceAndConnect()
            // for each childAsset in childAssets.references in cachedGroup that is not in
            // payload.childAssets.references call
            // notificationSubscriber.unsubscribeDeviceAndDisconnect()
            // in case of changes update the cachedGroup in groupCache

            // Get group id from payload
            String groupId = c8yMessage.getSourceId();

            // Get cached group
            ManagedObjectRepresentation cachedGroup = groupCache.get(groupId);

            if (cachedGroup == null) {
                log.warn("{} - Group with id {} not found in cache, skipping subscription update.", tenant, groupId);
                return processingResult;
            }

            // Extract child asset IDs from cached group
            List<String> cachedChildIds = new ArrayList<>();
            try {
                ManagedObjectReferenceCollectionRepresentation cachedChildAssets = cachedGroup.getChildAssets();
                for (ManagedObjectReferenceRepresentation child : cachedChildAssets.getReferences()) {
                    cachedChildIds.add(child.getManagedObject().getId().getValue());
                }

            } catch (Exception e) {
                log.warn("{} - Failed to extract cached child assets for group {}: {}", tenant, groupId,
                        e.getMessage());
            }

            // Extract child asset IDs from payload
            List<String> payloadChildIds = new ArrayList<>();
            try {
                Object payloadChildAssets = ((Map<?, ?>) c8yMessage.getParsedPayload().get("childAssets"));
                if (payloadChildAssets instanceof Map) {
                    Object payloadReferences = ((Map<?, ?>) payloadChildAssets).get("references");
                    if (payloadReferences instanceof List) {
                        for (Object ref : (List<?>) payloadReferences) {
                            if (ref instanceof Map) {
                                Object managedObject = ((Map<?, ?>) ref).get("managedObject");
                                if (managedObject instanceof Map) {
                                    Object id = ((Map<?, ?>) managedObject).get("id");
                                    if (id != null) {
                                        payloadChildIds.add(String.valueOf(id));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("{} - Failed to extract payload child assets for group {}: {}", tenant, groupId,
                        e.getMessage());
            }

            // Determine added and removed child assets
            List<String> toAdd = new ArrayList<>(payloadChildIds);
            toAdd.removeAll(cachedChildIds);

            List<String> toRemove = new ArrayList<>(cachedChildIds);
            toRemove.removeAll(payloadChildIds);

            // Subscribe new child assets
            for (String childId : toAdd) {
                try {
                    ManagedObjectRepresentation childMO = notificationSubscriber.getConfigurationRegistry()
                            .getC8yAgent().getManagedObjectForId(tenant, childId);
                    if (childMO != null) {
                        Future<NotificationSubscriptionRepresentation> subResult = notificationSubscriber
                                .subscribeDeviceAndConnect(tenant, childMO, c8yMessage.getApi());
                        log.info("{} - Subscribed and connected child asset {} to group {}", tenant, childId, groupId);
                    } else {
                        log.warn("{} - Could not find ManagedObject for child asset {} to subscribe.", tenant, childId);
                    }
                } catch (Exception e) {
                    log.warn("{} - Failed to subscribe/connect child asset {}: {}", tenant, childId, e.getMessage());
                    e.printStackTrace();
                }
            }

            // Unsubscribe removed child assets
            for (String childId : toRemove) {
                try {
                    ManagedObjectRepresentation childMO = notificationSubscriber.getConfigurationRegistry()
                            .getC8yAgent().getManagedObjectForId(tenant, childId);
                    if (childMO != null) {
                        notificationSubscriber.unsubscribeDeviceAndDisconnect(tenant, childMO);
                        log.info("{} - Unsubscribed and disconnected child asset {} from group {}", tenant, childId,
                                groupId);
                    } else {
                        log.warn("{} - Could not find ManagedObject for child asset {} to unsubscribe.", tenant,
                                childId);
                    }
                } catch (Exception e) {
                    log.warn("{} - Failed to unsubscribe/disconnect child asset {}: {}", tenant, childId,
                            e.getMessage());
                }
            }

            // If there were changes, update the cache
            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                ManagedObjectRepresentation updatedGroup = notificationSubscriber.getConfigurationRegistry()
                        .getC8yAgent().getManagedObjectForId(tenant, groupId);
                groupCache.put(updatedGroup.getId().getValue(), updatedGroup);

                log.info("{} - Updated group cache for group {}", tenant, groupId);
            }

            // TODO: Return a future so it can be blocked for QoS 1 or 2
            return processingResult;
        }
    }

    public void addGroupToCache(ManagedObjectRepresentation groupMO) {
        this.groupCache.put(groupMO.getId().getValue(), groupMO);
        log.debug("{} - Added group to cache: {}", tenant, groupMO.getId().getValue());
    }

    public void removeGroupFromCache(ManagedObjectRepresentation groupMO) {
        this.groupCache.remove(groupMO.getId().getValue());
        log.debug("{} - Removed group from cache: {}", tenant, groupMO.getId().getValue());
    }

    public static class UpdateSubscriptionDeviceTypeTask<T>
            implements Callable<List<Future<NotificationSubscriptionRepresentation>>> {
        C8YMessage c8yMessage;
        ConfigurationRegistry configurationRegistry;
        C8YNotificationSubscriber notificationSubscriber;
        Map<String, ManagedObjectRepresentation> groupCache;

        public UpdateSubscriptionDeviceTypeTask(ConfigurationRegistry configurationRegistry,
                C8YMessage c8yMessage, Map<String, ManagedObjectRepresentation> groupCache) {
            this.c8yMessage = c8yMessage;
            this.configurationRegistry = configurationRegistry;
            this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
            this.groupCache = groupCache;
        }

        @Override
        public List<Future<NotificationSubscriptionRepresentation>> call() throws Exception {
            List<Future<NotificationSubscriptionRepresentation>> processingResult = new ArrayList<>();
            String tenant = c8yMessage.getTenant();
            String deviceId = c8yMessage.getSourceId();
            Object typeObj = c8yMessage.getParsedPayload().get("type");
            if (typeObj == null) {
                log.warn("{} - No type found in payload, skipping subscription update.", tenant);
                return processingResult;
            }
            String type = String.valueOf(typeObj);

            // Subscribe new asset
            try {
                ManagedObjectRepresentation newMO = new ManagedObjectRepresentation();
                newMO.setId(GId.asGId(deviceId));

                Future<NotificationSubscriptionRepresentation> subResult = notificationSubscriber
                        .subscribeDeviceAndConnect(tenant, newMO, c8yMessage.getApi());
                log.info("{} - Subscribed and connected new asset {} of type {}", tenant, deviceId, type);

            } catch (Exception e) {
                log.warn("{} - Failed to subscribe/connect new asset {}: {}", tenant, deviceId, e.getMessage());
                e.printStackTrace();
            }

            // TODO: Return a future so it can be blocked for QoS 1 or 2
            return processingResult;
        }
    }
}
