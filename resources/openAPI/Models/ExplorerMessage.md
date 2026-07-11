# ExplorerMessage
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **direction** | **String** | Direction of the message: INBOUND (broker → C8Y) or OUTBOUND (C8Y → broker) | [optional] [default to null] |
| **topic** | **String** | The topic on which the message was received or sent | [optional] [default to null] |
| **connectorIdentifier** | **String** | Unique identifier of the connector that received the message | [optional] [default to null] |
| **clientId** | **String** | Client identifier of the broker client that sent the message | [optional] [default to null] |
| **connectorName** | **String** | Display name of the connector | [optional] [default to null] |
| **receivedAt** | **Long** | Epoch milliseconds when the message was received | [optional] [default to null] |
| **payload** | **String** | Message payload as UTF-8 string, or Base64-encoded for binary payloads | [optional] [default to null] |
| **binary** | **Boolean** | true if the original payload was binary and has been Base64-encoded | [optional] [default to null] |
| **sourceId** | **String** | C8Y source device ID (outbound only) | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

