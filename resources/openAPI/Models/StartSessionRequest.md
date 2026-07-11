# StartSessionRequest
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **connectorIdentifier** | **String** | Identifier of the inbound connector to listen on (required for INBOUND, ignored for OUTBOUND) | [optional] [default to null] |
| **topic** | **String** | Topic to subscribe to (MQTT wildcards supported) | [default to null] |
| **maxMessages** | **Integer** | Maximum number of messages to buffer (1–500). Defaults to 50. | [optional] [default to null] |
| **direction** | **String** | Direction to capture: INBOUND (broker → C8Y) or OUTBOUND (C8Y → broker). Defaults to INBOUND. | [optional] [default to null] |
| **sourceId** | **String** | C8Y managed object ID (device or group) for outbound notifications (OUTBOUND only; required — without a source ID no Notification 2.0 subscription is created and no events will be captured). | [optional] [default to null] |
| **deviceType** | **String** | C8Y device type filter (OUTBOUND only; optional). When set, only messages from devices whose type matches this value are captured. | [optional] [default to null] |
| **sessionTTLMinutes** | **Integer** | Session TTL in minutes. Overrides the tenant-wide default. Sessions expire when not polled for longer than this value. | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

