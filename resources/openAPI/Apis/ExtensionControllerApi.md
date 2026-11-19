# ExtensionControllerApi

All URIs are relative to */service/dynamic-mapper-service*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deleteProcessorExtension**](ExtensionControllerApi.md#deleteProcessorExtension) | **DELETE** /extension/{extensionName} | Delete a processor extension |
| [**getProcessorExtension**](ExtensionControllerApi.md#getProcessorExtension) | **GET** /extension/{extensionName} | Get a specific processor extension |
| [**getProcessorExtensions**](ExtensionControllerApi.md#getProcessorExtensions) | **GET** /extension | Get all processor extensions |
| [**uploadProcessorExtension**](ExtensionControllerApi.md#uploadProcessorExtension) | **POST** /extension | Upload a processor extension |


<a name="deleteProcessorExtension"></a>
# **deleteProcessorExtension**
> Extension deleteProcessorExtension(extensionName)

Delete a processor extension

    Deletes a processor extension from the system. This will remove the extension and make it unavailable for use in mappings. Only external extensions can be deleted - built-in extensions cannot be removed.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **extensionName** | **String**| The unique name of the extension to delete | [default to null] |

### Return type

[**Extension**](../Models/Extension.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getProcessorExtension"></a>
# **getProcessorExtension**
> Extension getProcessorExtension(extensionName)

Get a specific processor extension

    Retrieves detailed information about a specific processor extension including its configuration, status, and available entry points.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **extensionName** | **String**| The unique name of the extension to retrieve | [default to null] |

### Return type

[**Extension**](../Models/Extension.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getProcessorExtensions"></a>
# **getProcessorExtensions**
> Object getProcessorExtensions()

Get all processor extensions

    Retrieves all available processor extensions for the current tenant. Extensions provide custom data transformation and processing capabilities that can be used in mappings.

### Parameters
This endpoint does not need any parameter.

### Return type

**Object**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="uploadProcessorExtension"></a>
# **uploadProcessorExtension**
> Extension uploadProcessorExtension(file, name, overwrite)

Upload a processor extension

    Uploads the JAR of a processor extension, registers it under a name and loads it for the current tenant. The JAR must contain an extension-external.yaml that lists its extensions. If no extension can be loaded from the file, a new upload is rolled back. Mappings refer to the extension by its name.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **file** | **File**| The extension JAR | [default to null] |
| **name** | **String**| The name mappings refer to. Defaults to the file name without \".jar\". | [optional] [default to null] |
| **overwrite** | **Boolean**| Replace the file of an existing external extension with the same name | [optional] [default to false] |

### Return type

[**Extension**](../Models/Extension.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: multipart/form-data
- **Accept**: application/json
