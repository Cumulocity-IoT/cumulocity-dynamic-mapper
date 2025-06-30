# OperationControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**runOperation**](OperationControllerApi.md#runOperation) | **POST** /operation | Execute a service operation |


<a name="runOperation"></a>
# **runOperation**
> runOperation(ServiceOperation)

Execute a service operation

    Executes various administrative and operational tasks such as reloading mappings, connecting/disconnecting connectors, managing caches, and other maintenance operations. Different operations require different permission levels.  **Please note:** Each operation may have specific requirements and permissions. Ensure that the user has the necessary roles to perform the requested operation. &#x60;ROLE_DYNAMIC_MAPPER_CREATE&#x60; Operations: - &#x60;RELOAD_MAPPINGS&#x60;: Reloads all mappings for the current tenant. - &#x60;ACTIVATE_MAPPING&#x60;: Activates or deactivates a mapping. - &#x60;APPLY_MAPPING_FILTER&#x60;: Applies a filter to a mapping. - &#x60;DEBUG_MAPPING&#x60;: Enables or disables debug mode for a mapping. - &#x60;SNOOP_MAPPING&#x60;: Enables or disables snooping for a mapping. - &#x60;SNOOP_RESET&#x60;: Resets snooping for a mapping. - &#x60;REFRESH_STATUS_MAPPING&#x60;: Refreshes the status of all mappings. - &#x60;ADD_SAMPLE_MAPPINGS&#x60;: Adds sample mappings for inbound or outbound direction. - &#x60;COPY_SNOOPED_SOURCE_TEMPLATE&#x60;: Copies the source template from a snooped mapping.   &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; Operations: - &#x60;CONNECT&#x60;: Connects a specific connector. - &#x60;DISCONNECT&#x60;: Disconnects a specific connector. - &#x60;RESET_STATISTICS_MAPPING&#x60;: Resets statistics for all mappings. - &#x60;RESET_DEPLOYMENT_MAP&#x60;: Resets the deployment map for the current tenant. - &#x60;RELOAD_EXTENSIONS&#x60;: Reloads all extensions for the current tenant. - &#x60;REFRESH_NOTIFICATIONS_SUBSCRIPTIONS&#x60;: Refreshes notification subscriptions for the current tenant. - &#x60;CLEAR_CACHE&#x60;: Clears a specific cache (e.g., inbound ID cache, inventory cache). - &#x60;INIT_CODE_TEMPLATES&#x60;: Initializes code templates for the current tenant.  

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **ServiceOperation** | [**ServiceOperation**](../Models/ServiceOperation.md)| Service operation to execute with parameters | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

