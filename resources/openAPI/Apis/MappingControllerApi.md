# MappingControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createMapping1**](MappingControllerApi.md#createMapping1) | **POST** /mapping |  |
| [**deleteMapping**](MappingControllerApi.md#deleteMapping) | **DELETE** /mapping/{id} |  |
| [**getMapping**](MappingControllerApi.md#getMapping) | **GET** /mapping/{id} |  |
| [**getMappings**](MappingControllerApi.md#getMappings) | **GET** /mapping |  |
| [**updateMapping**](MappingControllerApi.md#updateMapping) | **PUT** /mapping/{id} |  |


<a name="createMapping1"></a>
# **createMapping1**
> Mapping createMapping1(Mapping)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **Mapping** | [**Mapping**](../Models/Mapping.md)|  | |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="deleteMapping"></a>
# **deleteMapping**
> String deleteMapping(id)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

**String**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getMapping"></a>
# **getMapping**
> Mapping getMapping(id)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getMappings"></a>
# **getMappings**
> List getMappings(direction)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **direction** | **String**|  | [optional] [default to null] [enum: INBOUND, OUTBOUND, UNSPECIFIED] |

### Return type

[**List**](../Models/Mapping.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateMapping"></a>
# **updateMapping**
> Mapping updateMapping(id, Mapping)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **Mapping** | [**Mapping**](../Models/Mapping.md)|  | |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

