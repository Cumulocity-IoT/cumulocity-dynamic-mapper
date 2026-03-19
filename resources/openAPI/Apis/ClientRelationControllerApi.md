# ClientRelationControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**addOrUpdateClientRelations**](ClientRelationControllerApi.md#addOrUpdateClientRelations) | **PUT** /relation/client/{clientId} | Add or update client relations |
| [**clearAllClientRelations**](ClientRelationControllerApi.md#clearAllClientRelations) | **DELETE** /relation/client | Clear all client relations |
| [**getAllClientRelations**](ClientRelationControllerApi.md#getAllClientRelations) | **GET** /relation/client | Get all client relations |
| [**getAllClients**](ClientRelationControllerApi.md#getAllClients) | **GET** /relation/clients | Get all clients |
| [**getClientForDevice**](ClientRelationControllerApi.md#getClientForDevice) | **GET** /relation/device/{deviceId}/client | Get client for device |
| [**getDevicesForClient**](ClientRelationControllerApi.md#getDevicesForClient) | **GET** /relation/client/{clientId}/devices | Get devices for client |
| [**removeAllRelationsForClient**](ClientRelationControllerApi.md#removeAllRelationsForClient) | **DELETE** /relation/client/{clientId}/delete | Remove all relations for client |
| [**removeRelationForDevice**](ClientRelationControllerApi.md#removeRelationForDevice) | **DELETE** /relation/device/{deviceId} | Remove relation for device |


<a name="addOrUpdateClientRelations"></a>
# **addOrUpdateClientRelations**
> Map addOrUpdateClientRelations(clientId, request\_body)

Add or update client relations

    Adds or updates the relation mapping for a specific MQTT client, associating devices with the client.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**| The MQTT client identifier | [default to null] |
| **request\_body** | [**Map**](../Models/object.md)|  | |

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="clearAllClientRelations"></a>
# **clearAllClientRelations**
> clearAllClientRelations()

Clear all client relations

    Removes all device-to-client relations.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters
This endpoint does not need any parameter.

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getAllClientRelations"></a>
# **getAllClientRelations**
> Map getAllClientRelations()

Get all client relations

    Retrieves all device-to-client relations for outbound mappings.

### Parameters
This endpoint does not need any parameter.

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getAllClients"></a>
# **getAllClients**
> Map getAllClients()

Get all clients

    Retrieves a list of all MQTT client identifiers that have device relations.

### Parameters
This endpoint does not need any parameter.

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getClientForDevice"></a>
# **getClientForDevice**
> Map getClientForDevice(deviceId)

Get client for device

    Retrieves the MQTT client associated with a specific device.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**| The device identifier | [default to null] |

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getDevicesForClient"></a>
# **getDevicesForClient**
> Map getDevicesForClient(clientId)

Get devices for client

    Retrieves all devices associated with a specific MQTT client.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**| The MQTT client identifier | [default to null] |

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="removeAllRelationsForClient"></a>
# **removeAllRelationsForClient**
> removeAllRelationsForClient(clientId)

Remove all relations for client

    Removes all device relations for a specific MQTT client.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **clientId** | **String**| The MQTT client identifier | [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="removeRelationForDevice"></a>
# **removeRelationForDevice**
> removeRelationForDevice(deviceId)

Remove relation for device

    Removes the client relation for a specific device.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**| The device identifier | [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

