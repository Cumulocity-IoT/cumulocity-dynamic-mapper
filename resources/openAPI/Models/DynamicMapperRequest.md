# DynamicMapperRequest
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **predecessor** | **Integer** | Index of the preceding request in a chain (-1 if none) | [optional] [default to null] |
| **method** | **String** | HTTP method used for the request | [optional] [default to null] |
| **api** | **String** | Target Cumulocity IoT API | [optional] [default to null] |
| **publishTopic** | **String** | MQTT topic to publish to (for broker connectors) | [optional] [default to null] |
| **retain** | **Boolean** | Whether the MQTT message should be retained | [optional] [default to null] |
| **sourceId** | **String** | Cumulocity internal device source ID | [optional] [default to null] |
| **externalId** | **String** | External device identifier | [optional] [default to null] |
| **externalIdType** | **String** | Type of the external device identifier | [optional] [default to null] |
| **request** | **String** | Raw request payload sent to the target system | [optional] [default to null] |
| **requestCumulocity** | **String** | Request payload with Cumulocity source identifier populated (for internal connectors) | [optional] [default to null] |
| **pathCumulocity** | **String** | Path used for the Cumulocity API request | [optional] [default to null] |
| **response** | **String** | Response received from the target system | [optional] [default to null] |
| **error** | [**DynamicMapperRequest_error**](DynamicMapperRequest_error.md) |  | [optional] [default to null] |
| **binaryPayload** | **byte[]** | Pre-encoded binary payload (e.g. SparkPlug B proto bytes). When set, connectors use this instead of encoding request as UTF-8. | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

