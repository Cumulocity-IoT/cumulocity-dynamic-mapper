# DeploymentControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getDeploymentMap**](DeploymentControllerApi.md#getDeploymentMap) | **GET** /deployment/defined |  |
| [**getDeploymentMapEntry**](DeploymentControllerApi.md#getDeploymentMapEntry) | **GET** /deployment/defined/{mappingIdentifier} |  |
| [**getMappingsDeployed**](DeploymentControllerApi.md#getMappingsDeployed) | **GET** /deployment/effective |  |
| [**updateDeploymentMapEntry**](DeploymentControllerApi.md#updateDeploymentMapEntry) | **PUT** /deployment/defined/{mappingIdentifier} |  |


<a name="getDeploymentMap"></a>
# **getDeploymentMap**
> Map getDeploymentMap()



### Parameters
This endpoint does not need any parameter.

### Return type

[**Map**](../Models/array.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: */*

<a name="getDeploymentMapEntry"></a>
# **getDeploymentMapEntry**
> List getDeploymentMapEntry(mappingIdentifier)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **mappingIdentifier** | **String**|  | [default to null] |

### Return type

**List**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: */*

<a name="getMappingsDeployed"></a>
# **getMappingsDeployed**
> Map getMappingsDeployed()



### Parameters
This endpoint does not need any parameter.

### Return type

[**Map**](../Models/DeploymentMapEntry.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateDeploymentMapEntry"></a>
# **updateDeploymentMapEntry**
> String updateDeploymentMapEntry(mappingIdentifier, request\_body)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **mappingIdentifier** | **String**|  | [default to null] |
| **request\_body** | [**List**](../Models/string.md)|  | |

### Return type

**String**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: */*

