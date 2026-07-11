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
| **deviceIsolationMQTTServiceEnabled** | **Boolean** | Flag to check if the device isolation for messages on over the Cumulocity MQTT Service is enabled | [default to null] |
| **inboundExternalIdCacheSize** | **Integer** | Size of the cache for inbound external ID lookups. Set to 0 to disable caching. | [default to null] |
| **inboundExternalIdCacheRetention** | **Integer** | Retention time in hours for inbound external ID cache entries. | [default to null] |
| **inventoryCacheSize** | **Integer** | Size of the inventory cache for device lookups. Set to 0 to disable caching. | [default to null] |
| **inventoryCacheRetention** | **Integer** | Retention time in hours for inventory cache entries. | [default to null] |
| **inventoryFragmentsToCache** | **List** | List of inventory fragments to include in cache for better performance. Entries are exact fragment names or glob patterns using &#39;*&#39; (any sequence) and &#39;?&#39; (single character). Examples: c8y_IsDevice, c8y_Hardware, sparkPlugB_DBIRTH_* | [default to null] |
| **maxCPUTimeMS** | **Integer** | Maximum CPU time in milliseconds allowed for code execution in mappings. Prevents infinite loops and excessive processing. | [default to null] |
| **pipelineTimeoutMS** | **Integer** | Maximum end-to-end wall-clock time in milliseconds the system waits for a Smart Function to finish processing a message — including JavaScript execution and any Cumulocity API calls. Must be greater than maxCPUTimeMS. | [default to null] |
| **jsonataAgent** | **String** | Name of jsonata agent to be used when generating substitutions. The needs to be defined in the AI Agent Manager. | [optional] [default to null] |
| **javaScriptAgent** | **String** | Deprecated: Name of javaScript agent for Substitution As Code (no longer supported). Kept for backward compatibility. | [optional] [default to null] |
| **smartFunctionAgent** | **String** | Name of javaScript SmartFunction agent to be used when generating Cumulocity API requests as JavaScript code. The needs to be defined in the AI Agent Manager. | [optional] [default to null] |
| **flowStateRetention** | **Integer** | Retention time in minutes for Smart Function and Java Extension flow state entries. Set to 0 to disable TTL. | [default to null] |
| **suppressDeprecationWarning** | **Boolean** | Suppress deprecation warning in UI. | [default to null] |
| **acceptedDeprecationNotice** | **String** | Holds the version string of the last accepted SUBSTITUTION_AS_CODE deprecation notice (e.g. &#39;6.2.0&#39;). If the value matches the current release version the notice is not shown again. A new version string triggers a new acceptance. | [optional] [default to null] |
| **supportESM** | **Boolean** | Enable ECMAScript Module (ESM) support for JavaScript code. When true, mapping code may use &#39;export function&#39; syntax and is evaluated as an ES module. When false (default), code runs in flat-script mode where export keywords are stripped. | [default to null] |
| **cacheAliasMaps** | **Boolean** | Automatically include the sparkPlugB_NBIRTH and sparkPlugB_DBIRTH fragments in the inventory cache for all cached managed objects. When enabled, these fragments are cached transparently alongside the fragments listed in inventoryFragmentsToCache, without requiring them to be added to that list manually. | [default to null] |
| **externalIdBinding** | **Boolean** | Enable binding of external IDs during managed object creation in a single request (Cumulocity platform &gt;&#x3D; May 2026). Disable on older instances such as Cumulocity Edge that do not yet support this feature. | [default to null] |
| **mappingVersionRetention** | **Integer** | Number of versions to retain per mapping line. When a new version is published, the oldest versions beyond this limit are pruned; the active version is never pruned. | [default to null] |
| **engineRotationThreshold** | **Integer** | Number of GraalVM context creations after which the shared Engine is rotated for a tenant to reclaim JVM Metaspace. Lower values free memory more frequently; higher values retain JIT warm-up longer. | [default to null] |
| **engineMaxAgeMinutes** | **Integer** | Maximum wall-clock age in minutes of the GraalVM Engine before it is rotated, regardless of context-creation count. Prevents low-traffic tenants from accumulating unbounded Metaspace over time. | [default to null] |
| **codeTemplates** | [**Map**](CodeTemplate.md) | Map of code templates used for custom processing logic in mappings | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

