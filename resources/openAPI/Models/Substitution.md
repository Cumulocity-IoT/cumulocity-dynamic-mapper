# Substitution
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **pathSource** | **String** | JSONPath expression to extract data from the source payload. Supports standard JSONPath syntax including: - Root reference: $ - Property access: $.temperature, $.device.id - Array access: $.readings[0], $.sensors[*].value - Wildcards: $.devices.*.name - Filters: $.readings[?(@.type &#x3D;&#x3D; &#39;temperature&#39;)]  | [default to null] |
| **pathTarget** | **String** | JSONPath expression defining where to place the extracted data in the target payload. Can reference: - Static paths: $.temperature.value - Device identity: _IDENTITY_.c8ySourceId, _IDENTITY_.externalId - Topic levels: _TOPIC_LEVEL_[0], _TOPIC_LEVEL_[1] - Context data: _CONTEXT_DATA_.timestamp  | [default to null] |
| **repairStrategy** | **String** | Strategy to handle data extraction and transformation edge cases | [default to null] |
| **expandArray** | **Boolean** | Whether to expand arrays by creating multiple target objects (one for each array element) instead of copying the entire array | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

