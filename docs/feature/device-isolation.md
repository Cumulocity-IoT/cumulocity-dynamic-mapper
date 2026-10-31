# Device isolation and client relations

With several MQTT clients on one tenant, device isolation makes an outbound message reach only the
client that owns the target device. The mapper keeps a **device to client map** and hands it to the
Cumulocity MQTT Service, which does the filtering. This page covers the switch, the map, and the
screen and endpoints that edit it. The connector side is in
[`connector-mqtt-service.md`](connector-mqtt-service.md#device-isolation).

---

## Requirements

**What it is for.** Keeping a client from receiving messages addressed to devices of another client when
many clients share a tenant.

- **It is a tenant-wide switch, off by default.** Service configuration setting *Device Isolation
  Cumulocity MQTT Service*. With it off, no relation is learned and the map is not sent.
- **Changing the switch needs the microservice to be unsubscribed and subscribed again** for the tenant.
- **A client owns a device once the mapper has seen the two together.** When an inbound message from a
  client is resolved to a device, the client and the device are related. A later message from another
  client moves the device to that client. Test runs and dry runs never create relations.
- **Relations can be edited by hand.** A user with the Create or Admin role can assign devices to a
  client, remove the relation of one device, remove all relations of one client, or clear all.
- **A deleted device loses its relation.**
- **Enforcement is by Cumulocity MQTT Service, not by the mapper.** A relation counts only after it has
  been written to the platform. That happens about once an hour, so a new or changed relation can take up to
  an hour to take effect and can be lost if the service stops before the next write.
- **Removed relations are removed from the platform copy too.**
- **The relation page shows only when the switch is on.** Its data calls need outbound mapping to be
  enabled; the service refuses them otherwise.

**Non-goals.** The mapper does not authenticate clients and does not check on the outbound path whether
a client may receive a message. Isolation exists only for the Cumulocity MQTT Service connector. The write
interval is not configurable and there is no "send now".

---

## Implementation

### Parts

| Part | Role |
|---|---|
| `ServiceConfiguration.deviceIsolationMQTTServiceEnabled` | The switch; exposed to the UI as a feature flag. |
| [`TenantRegistry`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/TenantRegistry.java) | In-memory map `tenant → deviceId → clientId` and the id of the persisted managed object. |
| [`DeviceToClientMapService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/mapping/DeviceToClientMapService.java) | Writes the map to the inventory. |
| [`ClientRelationController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/ClientRelationController.java) | REST under `/relation`. |
| [`BootstrapService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/BootstrapService.java) | Loads the map at tenant start, runs the hourly write (`sendDeviceToClientMap`, cron `0 30 * * * *`). |
| [`SubstitutionResultInboundProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/SubstitutionResultInboundProcessor.java), [`SendInboundProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/processor/SendInboundProcessor.java) | Learn relations while processing inbound messages. |
| [`device-client-map.component.ts`](../../dynamic-mapper-ui/src/mapping/client-relation/device-client-map.component.ts) | The *Client relation outbound* page. |

### Where the client id comes from

`ProcessingContext.getClientId()` is set from the connector message. The Cumulocity MQTT Service connector
reads it from the Pulsar message property `PULSAR_PROPERTY_CLIENT_ID`
([`MQTTServicePulsarCallback`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/pulsar/MQTTServicePulsarCallback.java)).
The MQTT 5 connector reads a user property named `clientId`; MQTT 3 has none, so it never learns relations.

### Learning

With the switch on, not testing, and a non-null client id, `addOrUpdateClientRelation(tenant, clientId, deviceId)`
runs at three places: after an external id resolves to a device in the substitution step, after a `c8ySourceId`
substitution, and in the send step after the source id is resolved or the device is upserted. The latest
write wins, one client per device.

### Persistence

The map lives in a managed object with external id `d11r_deviceToClientMap_id` (type and fragment
`d11r_deviceToClientMap`). `BootstrapService` creates or loads it at tenant start when the switch is on, and the
hourly job does the same if the switch was turned on later. `sendToInventory` reads the stored map first and
writes nothing when it equals the memory. Because the inventory merges nested fragments, entries that were
removed in memory are sent as `null` so they are removed there too.

Cleanup: `C8YAgent.evictDeletedManagedObjectFromCaches` removes the relation of a deleted device. The tenant
unsubscribe and the `CLEAR_CACHE_DEVICE_TO_CLIENT` operation (Admin) empty the in-memory map; the stored copy
follows at the next write.

### Endpoints

| Method and path | Role | Effect |
|---|---|---|
| `PUT /relation/client/{clientId}` | Admin or Create | Body is a list of device ids; each is related to the client. An empty list does nothing. |
| `GET /relation/client` | any | `{ totalRelations, relations: [{ key, value }] }`. |
| `GET /relation/client/{clientId}/devices` | any | Devices of the client, `404` if none. |
| `GET /relation/device/{deviceId}/client` | any | Client of the device, `404` if none. |
| `GET /relation/clients` | any | Distinct client ids. |
| `DELETE /relation/device/{deviceId}` | Admin or Create | Removes one relation, `404` if none. |
| `DELETE /relation/client/{clientId}` | Admin or Create | Removes all relations of the client, `404` if none. |
| `DELETE /relation/client` | Admin or Create | Clears all. |

Every endpoint first calls `validateOutboundMappingEnabled` and fails if outbound mapping is off. None
checks the isolation switch.

### Traps

- **Edits are memory-only until the hourly write.** A `PUT` or `DELETE` on `/relation` changes the in-memory
  map and nothing else. The platform copy is written at minute 30 of each hour.
- **The "delete all" button calls a path the service does not have.** The UI sends `DELETE /relation/relations`;
  the service maps `DELETE /relation/client`
  ([`client-relation.service.ts`](../../dynamic-mapper-ui/src/mapping/core/client-relation.service.ts)).
- **The relation endpoints do not check the isolation switch.** Relations can be written while isolation is
  off; they are then never sent.
- **Several service instances overwrite each other.** Each instance writes its own view and sends `null`
  for entries it does not know.
- **Edits through the endpoints do not verify device ids.** `PUT` accepts ids of devices that do not exist;
  the controller catches `DeviceNotFoundException` but nothing in the call chain throws it.
