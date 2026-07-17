# ClientRelationControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**addOrUpdateClientRelations**](ClientRelationControllerApi.md#addOrUpdateClientRelations) | **PUT** /relation/client/{clientId} | Update client relation for client |
| [**clearAllClientRelations**](ClientRelationControllerApi.md#clearAllClientRelations) | **DELETE** /relation/client | Clear all client relations |
| [**getAllClientRelations**](ClientRelationControllerApi.md#getAllClientRelations) | **GET** /relation/client | Get all client relations |
| [**getAllClients**](ClientRelationControllerApi.md#getAllClients) | **GET** /relation/clients | Get all clients |
| [**getClientForDevice**](ClientRelationControllerApi.md#getClientForDevice) | **GET** /relation/device/{deviceId}/client | Get client mapping for device |
| [**getDevicesForClient**](ClientRelationControllerApi.md#getDevicesForClient) | **GET** /relation/client/{clientId}/devices | Get all devices mapped to a specific client |
| [**removeAllRelationsForClient**](ClientRelationControllerApi.md#removeAllRelationsForClient) | **DELETE** /relation/client/{clientId} | Remove all device mappings for a specific client |
| [**removeRelationForDevice**](ClientRelationControllerApi.md#removeRelationForDevice) | **DELETE** /relation/device/{deviceId} | Remove client mapping for specific device |


<a name="addOrUpdateClientRelations"></a>
# **addOrUpdateClientRelations**
> Map addOrUpdateClientRelations(clientId, request\_body)

Update client relation for client

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**|  | [default to null] |
| **request\_body** | [**List**](../Models/string.md)|  | |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="clearAllClientRelations"></a>
# **clearAllClientRelations**
> Map clearAllClientRelations()

Clear all client relations

### Parameters
This endpoint does not need any parameter.

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getAllClientRelations"></a>
# **getAllClientRelations**
> Map getAllClientRelations()

Get all client relations

### Parameters
This endpoint does not need any parameter.

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getAllClients"></a>
# **getAllClients**
> Map getAllClients()

Get all clients

### Parameters
This endpoint does not need any parameter.

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getClientForDevice"></a>
# **getClientForDevice**
> Map getClientForDevice(deviceId)

Get client mapping for device

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getDevicesForClient"></a>
# **getDevicesForClient**
> Map getDevicesForClient(clientId)

Get all devices mapped to a specific client

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="removeAllRelationsForClient"></a>
# **removeAllRelationsForClient**
> Map removeAllRelationsForClient(clientId)

Remove all device mappings for a specific client

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="removeRelationForDevice"></a>
# **removeRelationForDevice**
> Map removeRelationForDevice(deviceId)

Remove client mapping for specific device

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

