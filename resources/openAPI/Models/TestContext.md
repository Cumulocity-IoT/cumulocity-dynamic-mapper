# TestContext
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **mapping** | [**Mapping**](Mapping.md) | Mapping to test | [default to null] |
| **payload** | **String** | JSON payload as string for test | [default to null] |
| **send** | **Boolean** | Send payload to backend | [optional] [default to null] |
| **createTestDevice** | **Boolean** | Ignored. A live test (send&#x3D;true) of an inbound mapping always uses a test device: created if missing (tagged with d11r_testDevice), otherwise the existing device with the same external ID. | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

