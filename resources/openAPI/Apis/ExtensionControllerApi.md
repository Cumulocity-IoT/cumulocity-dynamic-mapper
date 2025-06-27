# ExtensionControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deleteProcessorExtension**](ExtensionControllerApi.md#deleteProcessorExtension) | **DELETE** /extension/{extensionName} |  |
| [**getProcessorExtension**](ExtensionControllerApi.md#getProcessorExtension) | **GET** /extension/{extensionName} |  |
| [**getProcessorExtensions**](ExtensionControllerApi.md#getProcessorExtensions) | **GET** /extension |  |


<a name="deleteProcessorExtension"></a>
# **deleteProcessorExtension**
> Extension deleteProcessorExtension(extensionName)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **extensionName** | **String**|  | [default to null] |

### Return type

[**Extension**](../Models/Extension.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getProcessorExtension"></a>
# **getProcessorExtension**
> Extension getProcessorExtension(extensionName)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **extensionName** | **String**|  | [default to null] |

### Return type

[**Extension**](../Models/Extension.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getProcessorExtensions"></a>
# **getProcessorExtensions**
> Map getProcessorExtensions()



### Parameters
This endpoint does not need any parameter.

### Return type

[**Map**](../Models/Extension.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

