# MappingControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createMapping1**](MappingControllerApi.md#createMapping1) | **POST** /mapping | Create a new mapping |
| [**deleteMapping**](MappingControllerApi.md#deleteMapping) | **DELETE** /mapping/{id} | Delete a mapping |
| [**getMapping**](MappingControllerApi.md#getMapping) | **GET** /mapping/{id} | Get a specific mapping |
| [**getMappings**](MappingControllerApi.md#getMappings) | **GET** /mapping | Get all mappings |
| [**updateMapping**](MappingControllerApi.md#updateMapping) | **PUT** /mapping/{id} | Update an existing mapping |


<a name="createMapping1"></a>
# **createMapping1**
> Mapping createMapping1(Mapping)

Create a new mapping

    Creates a new mapping configuration. The mapping will be created in disabled state by default and needs to be activated separately. For INBOUND mappings, subscriptions will be created across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

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

Delete a mapping

    Deletes a mapping by its unique identifier. This will also remove all associated subscriptions and cache entries.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique identifier of the mapping to delete | [default to null] |

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

Get a specific mapping

    Retrieves a mapping by its unique identifier.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique identifier of the mapping | [default to null] |

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

Get all mappings

    Retrieves all mappings for the current tenant. Optionally filter by direction (INBOUND/OUTBOUND).

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **direction** | **String**| Filter mappings by direction | [optional] [default to null] [enum: INBOUND, OUTBOUND, UNSPECIFIED] |

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

Update an existing mapping

    Updates an existing mapping configuration. Note that active mappings cannot be updated - they must be deactivated first. For INBOUND mappings, subscriptions will be updated across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique identifier of the mapping to update | [default to null] |
| **Mapping** | [**Mapping**](../Models/Mapping.md)|  | |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

