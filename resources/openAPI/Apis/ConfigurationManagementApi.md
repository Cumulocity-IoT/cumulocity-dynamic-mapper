# ConfigurationManagementApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createCodeTemplate**](ConfigurationManagementApi.md#createCodeTemplate) | **POST** /configuration/code | Create code template |
| [**createConnectorConfiguration**](ConfigurationManagementApi.md#createConnectorConfiguration) | **POST** /configuration/connector/instance | Create connector configuration |
| [**deleteCodeTemplate**](ConfigurationManagementApi.md#deleteCodeTemplate) | **DELETE** /configuration/code/{id} | Delete code template |
| [**deleteConnectionConfiguration**](ConfigurationManagementApi.md#deleteConnectionConfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete connector configuration |
| [**getCodeTemplate**](ConfigurationManagementApi.md#getCodeTemplate) | **GET** /configuration/code/{id} | Get code template |
| [**getCodeTemplates**](ConfigurationManagementApi.md#getCodeTemplates) | **GET** /configuration/code | Get all code templates |
| [**getConnectionConfiguration**](ConfigurationManagementApi.md#getConnectionConfiguration) | **GET** /configuration/connector/instance/{identifier} | Get connector configuration |
| [**getConnectionConfigurations**](ConfigurationManagementApi.md#getConnectionConfigurations) | **GET** /configuration/connector/instance | Get connector configurations |
| [**getConnectorSpecifications**](ConfigurationManagementApi.md#getConnectorSpecifications) | **GET** /configuration/connector/specifications | Get connector specifications |
| [**getFeatures**](ConfigurationManagementApi.md#getFeatures) | **GET** /configuration/feature | Get feature flags |
| [**getServiceConfiguration**](ConfigurationManagementApi.md#getServiceConfiguration) | **GET** /configuration/service | Get service configuration |
| [**updateCodeTemplate**](ConfigurationManagementApi.md#updateCodeTemplate) | **PUT** /configuration/code/{id} | Update code template |
| [**updateConnectionConfiguration**](ConfigurationManagementApi.md#updateConnectionConfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update connector configuration |
| [**updateServiceConfiguration**](ConfigurationManagementApi.md#updateServiceConfiguration) | **PUT** /configuration/service | Update service configuration |


<a name="createCodeTemplate"></a>
# **createCodeTemplate**
> createCodeTemplate(CodeTemplate)

Create code template

    Creates a new code template for the current tenant.  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **CodeTemplate** | [**CodeTemplate**](../Models/CodeTemplate.md)|  | |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

<a name="createConnectorConfiguration"></a>
# **createConnectorConfiguration**
> String createConnectorConfiguration(ConnectorConfiguration)

Create connector configuration

    Creates a new connector configuration for the specified type. The connector will be created in disabled state and must be explicitly enabled through a separate operation.  **Note:** HTTP connectors cannot be created through this endpoint as they are system-managed.  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **ConnectorConfiguration** | [**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)| Connector configuration to be created | |

### Return type

**String**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: */*

<a name="deleteCodeTemplate"></a>
# **deleteCodeTemplate**
> CodeTemplate deleteCodeTemplate(id)

Delete code template

    Deletes the code template for the given ID.  **Note:** Internal (system) templates cannot be deleted.  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique ID of the code template | [default to null] |

### Return type

[**CodeTemplate**](../Models/CodeTemplate.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="deleteConnectionConfiguration"></a>
# **deleteConnectionConfiguration**
> deleteConnectionConfiguration(identifier)

Delete connector configuration

    Deletes the connector configuration for the given identifier.  **Prerequisites:** - The connector must be disabled before it can be deleted - HTTP connectors cannot be deleted as they are system-managed  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The unique identifier of the connector | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getCodeTemplate"></a>
# **getCodeTemplate**
> CodeTemplate getCodeTemplate(id)

Get code template

    Returns the code template for the given ID. Code templates provide reusable JavaScript code for custom processing in mappings.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique ID of the code template | [default to null] |

### Return type

[**CodeTemplate**](../Models/CodeTemplate.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getCodeTemplates"></a>
# **getCodeTemplates**
> Object getCodeTemplates()

Get all code templates

    Returns all code templates for the current tenant including both system and custom templates.

### Parameters
This endpoint does not need any parameter.

### Return type

**Object**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectionConfiguration"></a>
# **getConnectionConfiguration**
> ConnectorConfiguration getConnectionConfiguration(identifier)

Get connector configuration

    Returns the connector configuration for the given identifier. Sensitive properties are masked in the response.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The unique identifier of the connector | [default to null] |

### Return type

[**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectionConfigurations"></a>
# **getConnectionConfigurations**
> List getConnectionConfigurations(name)

Get connector configurations

    Returns a list of all connector configurations for the current tenant. Sensitive properties (passwords, tokens) are masked in the response. Optionally filter results by name using wildcards (* supported). 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| Filter by connector name (wildcards * supported) | [optional] [default to null] |

### Return type

[**List**](../Models/ConnectorConfiguration.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorSpecifications"></a>
# **getConnectorSpecifications**
> List getConnectorSpecifications()

Get connector specifications

    Returns all available connector specifications with their supported properties and capabilities. Use this endpoint to discover which connector types are available and their configuration requirements. 

### Parameters
This endpoint does not need any parameter.

### Return type

[**List**](../Models/ConnectorSpecification.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getFeatures"></a>
# **getFeatures**
> Feature getFeatures()

Get feature flags

    Returns feature flags indicating which functionality is available for the current tenant and user. This is useful for UI applications to conditionally enable/disable features.  **Feature Flags:** - &#x60;outputMappingEnabled&#x60;: Whether outbound mapping is available - &#x60;externalExtensionsEnabled&#x60;: Whether external processor extensions are supported - &#x60;userHasMappingCreateRole&#x60;: Whether user can create/modify mappings - &#x60;userHasMappingAdminRole&#x60;: Whether user has administrative privileges - &#x60;pulsarAvailable&#x60;: Whether pulsar is available - &#x60;deviceIsolationMQTTServiceEnabled&#x60;: Whether device siolation for Cumulocity MQTT Service is enabled 

### Parameters
This endpoint does not need any parameter.

### Return type

[**Feature**](../Models/Feature.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getServiceConfiguration"></a>
# **getServiceConfiguration**
> ServiceConfiguration getServiceConfiguration()

Get service configuration

    Retrieves the service configuration for the current tenant including feature flags, cache settings, and operational parameters.

### Parameters
This endpoint does not need any parameter.

### Return type

[**ServiceConfiguration**](../Models/ServiceConfiguration.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateCodeTemplate"></a>
# **updateCodeTemplate**
> updateCodeTemplate(id, CodeTemplate)

Update code template

    Updates the code template for the given ID with new JavaScript code.  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique ID of the code template | [default to null] |
| **CodeTemplate** | [**CodeTemplate**](../Models/CodeTemplate.md)|  | |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

<a name="updateConnectionConfiguration"></a>
# **updateConnectionConfiguration**
> ConnectorConfiguration updateConnectionConfiguration(identifier, ConnectorConfiguration)

Update connector configuration

    Updates the connector configuration for the given identifier.  **Sensitive Properties:** Properties marked as sensitive (like passwords) can be: - Updated by providing new values - Left unchanged by sending \&quot;****\&quot; as the value  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **identifier** | **String**| The unique identifier of the connector | [default to null] |
| **ConnectorConfiguration** | [**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)| Updated connector configuration | |

### Return type

[**ConnectorConfiguration**](../Models/ConnectorConfiguration.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateServiceConfiguration"></a>
# **updateServiceConfiguration**
> updateServiceConfiguration(request\_body)

Update service configuration

    Updates the service configuration for the current tenant.  **Important:** Changing outbound mapping settings will affect notification subscriptions and may trigger connector reconnections.  **Security:** Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60; 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **request\_body** | [**Map**](../Models/object.md)|  | |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: Not defined

