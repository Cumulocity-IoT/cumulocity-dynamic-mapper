# ConfigurationControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createCodeTemplate**](ConfigurationControllerApi.md#createCodeTemplate) | **POST** /configuration/code | Create a new code template |
| [**createConnectorConfiguration**](ConfigurationControllerApi.md#createConnectorConfiguration) | **POST** /configuration/connector/instance | Create a new connector configuration |
| [**deleteCodeTemplate**](ConfigurationControllerApi.md#deleteCodeTemplate) | **DELETE** /configuration/code/{id} | Delete a code template |
| [**deleteConnectionConfiguration**](ConfigurationControllerApi.md#deleteConnectionConfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete a connector configuration |
| [**getCodeTemplate**](ConfigurationControllerApi.md#getCodeTemplate) | **GET** /configuration/code/{id} | Get a code template |
| [**getCodeTemplates**](ConfigurationControllerApi.md#getCodeTemplates) | **GET** /configuration/code | Get all code templates |
| [**getConnectionConfiguration**](ConfigurationControllerApi.md#getConnectionConfiguration) | **GET** /configuration/connector/instance/{identifier} | Get a connector configuration |
| [**getConnectionConfigurations**](ConfigurationControllerApi.md#getConnectionConfigurations) | **GET** /configuration/connector/instance | Get all connector configurations |
| [**getConnectorSpecifications**](ConfigurationControllerApi.md#getConnectorSpecifications) | **GET** /configuration/connector/specifications | Get connectors with their specifications |
| [**getFeatures**](ConfigurationControllerApi.md#getFeatures) | **GET** /configuration/feature | Get the feature flags for the dynamic mapper service |
| [**getServiceConfiguration**](ConfigurationControllerApi.md#getServiceConfiguration) | **GET** /configuration/service | Get Service Configuration |
| [**updateCodeTemplate**](ConfigurationControllerApi.md#updateCodeTemplate) | **PUT** /configuration/code/{id} | Update a code template |
| [**updateConnectionConfiguration**](ConfigurationControllerApi.md#updateConnectionConfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update a connector configuration |
| [**updateServiceConfiguration**](ConfigurationControllerApi.md#updateServiceConfiguration) | **PUT** /configuration/service | Update the service configuration |


<a name="createCodeTemplate"></a>
# **createCodeTemplate**
> createCodeTemplate(CodeTemplate)

Create a new code template

    Creates a new code template for the current tenant.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **CodeTemplate** | [**CodeTemplate**](../Models/CodeTemplate.md)|  | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

<a name="createConnectorConfiguration"></a>
# **createConnectorConfiguration**
> String createConnectorConfiguration(ConnectorConfiguration)

Create a new connector configuration

    Creates a new connector configuration for the specified type. Required role: &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60;

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **ConnectorConfiguration** | [**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)| Connector configuration to be created | |

### Return type

**String**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: */*

<a name="deleteCodeTemplate"></a>
# **deleteCodeTemplate**
> CodeTemplate deleteCodeTemplate(id)

Delete a code template

    Deletes the code template for the given ID.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

[**CodeTemplate**](../Models/CodeTemplate.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="deleteConnectionConfiguration"></a>
# **deleteConnectionConfiguration**
> deleteConnectionConfiguration(identifier)

Delete a connector configuration

    Deletes the connector configuration for the given ID. Required role: &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60;. The connector must be disabled before it can be deleted.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The identifier of the connector | [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getCodeTemplate"></a>
# **getCodeTemplate**
> CodeTemplate getCodeTemplate(id)

Get a code template

    Returns the code template for the given ID.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

[**CodeTemplate**](../Models/CodeTemplate.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getCodeTemplates"></a>
# **getCodeTemplates**
> CodeTemplate getCodeTemplates()

Get all code templates

    Returns all code templates for the current tenant.

### Parameters
This endpoint does not need any parameter.

### Return type

[**CodeTemplate**](../Models/CodeTemplate.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectionConfiguration"></a>
# **getConnectionConfiguration**
> ConnectorConfiguration getConnectionConfiguration(identifier)

Get a connector configuration

    Returns the connector configuration for the given ID.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The identifier of the connector | [default to null] |

### Return type

[**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectionConfigurations"></a>
# **getConnectionConfigurations**
> List getConnectionConfigurations(name)

Get all connector configurations

    Returns a list of all connector configurations. Optionally, filter by name.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**|  | [optional] [default to null] |

### Return type

[**List**](../Models/ConnectorConfiguration.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorSpecifications"></a>
# **getConnectorSpecifications**
> List getConnectorSpecifications()

Get connectors with their specifications

    Returns all available connector specifications.

### Parameters
This endpoint does not need any parameter.

### Return type

[**List**](../Models/ConnectorSpecification.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getFeatures"></a>
# **getFeatures**
> Feature getFeatures()

Get the feature flags for the dynamic mapper service

    Returns features with an indication if some functionality is available or not. This is useful if you want to check for example if outbound Mapping is possible on a tenant or not.

### Parameters
This endpoint does not need any parameter.

### Return type

[**Feature**](../Models/Feature.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getServiceConfiguration"></a>
# **getServiceConfiguration**
> ServiceConfiguration getServiceConfiguration()

Get Service Configuration

    Retrieves the service configuration for tenant of the authenticated user.

### Parameters
This endpoint does not need any parameter.

### Return type

[**ServiceConfiguration**](../Models/ServiceConfiguration.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateCodeTemplate"></a>
# **updateCodeTemplate**
> updateCodeTemplate(id, CodeTemplate)

Update a code template

    Updates the code template for the given ID.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **CodeTemplate** | [**CodeTemplate**](../Models/CodeTemplate.md)|  | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

<a name="updateConnectionConfiguration"></a>
# **updateConnectionConfiguration**
> ConnectorConfiguration updateConnectionConfiguration(identifier, ConnectorConfiguration)

Update a connector configuration

    Updates the connector configuration for the given ID. Required role: &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60;

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The identifier of the connector | [default to null] |
| **ConnectorConfiguration** | [**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)| Connector configuration to be update | |

### Return type

[**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateServiceConfiguration"></a>
# **updateServiceConfiguration**
> updateServiceConfiguration(ServiceConfiguration)

Update the service configuration

    Updates the service configuration of the current tenant.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **ServiceConfiguration** | [**ServiceConfiguration**](../Models/ServiceConfiguration.md)|  | |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

