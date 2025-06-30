# DeploymentControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getDeploymentMap**](DeploymentControllerApi.md#getDeploymentMap) | **GET** /deployment/defined | Get complete deployment configuration |
| [**getDeploymentMapEntry**](DeploymentControllerApi.md#getDeploymentMapEntry) | **GET** /deployment/defined/{mappingIdentifier} | Get deployment configuration for mapping |
| [**getMappingsDeployed**](DeploymentControllerApi.md#getMappingsDeployed) | **GET** /deployment/effective | Get effective deployments |
| [**updateDeploymentMapEntry**](DeploymentControllerApi.md#updateDeploymentMapEntry) | **PUT** /deployment/defined/{mappingIdentifier} | Update deployment configuration for mapping |


<a name="getDeploymentMap"></a>
# **getDeploymentMap**
> Object getDeploymentMap()

Get complete deployment configuration

    Retrieves the complete deployment configuration map showing all mappings and which connectors they are configured to be deployed to.  **Response Format:** - Key: Mapping identifier - Value: List of connector identifiers  **Use Cases:** - Get overview of all deployment configurations - Export/backup deployment settings - Audit deployment assignments - Bulk deployment management 

### Parameters
This endpoint does not need any parameter.

### Return type

**Object**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getDeploymentMapEntry"></a>
# **getDeploymentMapEntry**
> List getDeploymentMapEntry(mappingIdentifier)

Get deployment configuration for mapping

    Retrieves the deployment configuration for a specific mapping, showing which connectors this mapping is configured to be deployed to.  **Note:** This shows the configured deployment, not necessarily the active runtime state. Use the effective deployment endpoint to see actual runtime deployment. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **mappingIdentifier** | **String**| Generated identifier for the mapping | [default to null] |

### Return type

[**List**](../Models/AnyType.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getMappingsDeployed"></a>
# **getMappingsDeployed**
> Object getMappingsDeployed()

Get effective deployments

    Retrieves the current effective deployment state by querying all active connectors to see which mappings are actually deployed and running. This shows the real-time deployment status across all connectors.  **Use Case:** - Monitor which mappings are currently active on each connector - Verify deployment consistency across connectors - Troubleshoot deployment issues  **Response Format:** - Key: Mapping identifier - Value: DeploymentMapEntry with connector details 

### Parameters
This endpoint does not need any parameter.

### Return type

**Object**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateDeploymentMapEntry"></a>
# **updateDeploymentMapEntry**
> updateDeploymentMapEntry(mappingIdentifier, request\_body)

Update deployment configuration for mapping

    Updates the deployment configuration for a specific mapping by specifying which connectors it should be deployed to. This defines the intended deployment state rather than the actual runtime state.  **Behavior:** - Defines which connectors should have this mapping active - Does not immediately deploy - requires separate activation - Overwrites existing deployment configuration for this mapping  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **mappingIdentifier** | **String**| Generated identifier for the mapping | [default to null] |
| **request\_body** | [**List**](../Models/AnyType.md)| List of connector identifiers where this mapping should be deployed | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

