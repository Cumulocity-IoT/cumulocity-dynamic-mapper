# ServiceConfiguration
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **logPayload** | **Boolean** | Enable logging of message payloads for debugging purposes. Caution: May expose sensitive data in logs. | [default to null] |
| **logSubstitution** | **Boolean** | Enable logging of field substitutions during mapping transformation for debugging. | [default to null] |
| **logConnectorErrorInBackend** | **Boolean** | Enable logging of connector errors in the backend system for monitoring and troubleshooting. | [default to null] |
| **sendConnectorLifecycle** | **Boolean** | Enable sending connector lifecycle events (connect/disconnect) to Cumulocity IoT. | [default to null] |
| **sendMappingStatus** | **Boolean** | Enable sending mapping execution status and statistics to Cumulocity IoT. | [default to null] |
| **sendSubscriptionEvents** | **Boolean** | Enable sending subscription events when mappings are activated/deactivated. | [default to null] |
| **sendNotificationLifecycle** | **Boolean** | Enable sending notification lifecycle events for outbound mapping subscriptions. | [default to null] |
| **externalExtensionEnabled** | **Boolean** | Enable support for external processor extensions that provide custom transformation capabilities. | [default to null] |
| **outboundMappingEnabled** | **Boolean** | Enable outbound mapping functionality for sending data from Cumulocity IoT to external systems. | [default to null] |
| **inboundExternalIdCacheSize** | **Integer** | Size of the cache for inbound external ID lookups. Set to 0 to disable caching. | [default to null] |
| **inboundExternalIdCacheRetention** | **Integer** | Retention time in hours for inbound external ID cache entries. | [default to null] |
| **inventoryCacheSize** | **Integer** | Size of the inventory cache for device lookups. Set to 0 to disable caching. | [default to null] |
| **inventoryCacheRetention** | **Integer** | Retention time in hours for inventory cache entries. | [default to null] |
| **inventoryFragmentsToCache** | **List** | List of inventory fragments to include in cache for better performance. Examples: c8y_IsDevice, c8y_Hardware, c8y_Mobile | [default to null] |
| **maxCPUTimeMS** | **Integer** | Maximum CPU time in milliseconds allowed for code execution in mappings. Prevents infinite loops and excessive processing. | [default to null] |
| **codeTemplates** | [**Map**](CodeTemplate.md) | Map of code templates used for custom processing logic in mappings | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

