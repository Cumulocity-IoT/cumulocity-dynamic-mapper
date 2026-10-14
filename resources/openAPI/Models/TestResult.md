# TestResult
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **requests** | [**List**](DynamicMapperRequest.md) | Generated requests resulting from the transformation | [default to null] |
| **errors** | **List** | List of errors | [default to null] |
| **warnings** | **List** | List of warnings | [default to null] |
| **logs** | **List** | List of log messages | [default to null] |
| **success** | **Boolean** | Was the test successful | [default to null] |
| **testDeviceId** | **String** | ID of the test device used by a live test (send&#x3D;true) of an inbound mapping: created in C8Y inventory, or the existing device with the same external ID | [optional] [default to null] |
| **testDeviceCreated** | **Boolean** | Whether the test device was created by this test (true) or already existed and was used as is (false). An existing device is not re-tagged d11r_testDevice. Only set when testDeviceId is set. | [optional] [default to null] |
| **testDeviceExternalId** | **String** | External ID the test device is registered under: &quot;d11r_liveTest_&quot; + the external ID the mapping produces, so a live test never uses the external ID of the real device. Only set when testDeviceId is set. | [optional] [default to null] |
| **key** | **String** | Broker message key produced by the transformation, e.g. the Kafka record key mapped to _CONTEXT_DATA_.key or set via transportFields.key. Null when the mapping produces no key. | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

