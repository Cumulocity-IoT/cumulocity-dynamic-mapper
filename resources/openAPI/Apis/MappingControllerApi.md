# MappingControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createMapping**](MappingControllerApi.md#createMapping) | **POST** /mapping | Create a new mapping |
| [**deleteDraft**](MappingControllerApi.md#deleteDraft) | **DELETE** /mapping/{id}/draft | Discard the draft of a mapping |
| [**deleteMapping**](MappingControllerApi.md#deleteMapping) | **DELETE** /mapping/{id} | Delete a mapping |
| [**deleteVersion**](MappingControllerApi.md#deleteVersion) | **DELETE** /mapping/{id}/version/{version} | Delete a version of a mapping |
| [**getDraft**](MappingControllerApi.md#getDraft) | **GET** /mapping/{id}/draft | Get the draft (working copy) of a mapping |
| [**getMapping**](MappingControllerApi.md#getMapping) | **GET** /mapping/{id} | Get a specific mapping |
| [**getMappings**](MappingControllerApi.md#getMappings) | **GET** /mapping | Get all mappings |
| [**getVersion**](MappingControllerApi.md#getVersion) | **GET** /mapping/{id}/version/{version} | Get a specific version of a mapping |
| [**getVersionCounts**](MappingControllerApi.md#getVersionCounts) | **GET** /mapping/version-counts | Get published version counts for all mappings |
| [**getVersions**](MappingControllerApi.md#getVersions) | **GET** /mapping/{id}/version | List versions of a mapping |
| [**publishDraft**](MappingControllerApi.md#publishDraft) | **POST** /mapping/{id}/publish | Publish the draft as a new version |
| [**saveDraft**](MappingControllerApi.md#saveDraft) | **PUT** /mapping/{id}/draft | Save edits into the draft of a mapping |
| [**suggestNextVersions**](MappingControllerApi.md#suggestNextVersions) | **GET** /mapping/{id}/version/suggest | Suggest the next semver labels for a mapping |
| [**updateMapping**](MappingControllerApi.md#updateMapping) | **PUT** /mapping/{id} | Update an existing mapping |
| [**updateVersionNote**](MappingControllerApi.md#updateVersionNote) | **PATCH** /mapping/{id}/version/{version} | Update a version&#39;s note |


<a name="createMapping"></a>
# **createMapping**
> Mapping createMapping(Mapping)

Create a new mapping

    Creates a new mapping configuration. The mapping will be created in disabled state by default and needs to be activated separately. For INBOUND mappings, subscriptions will be created across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **Mapping** | [**Mapping**](../Models/Mapping.md)|  | |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="deleteDraft"></a>
# **deleteDraft**
> deleteDraft(id)

Discard the draft of a mapping

    Permanently deletes the mapping line&#39;s current draft (working copy) without affecting published versions or the active configuration. No-op when there is no draft.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="deleteMapping"></a>
# **deleteMapping**
> String deleteMapping(id)

Delete a mapping

    Deletes a mapping by its unique identifier. This will also remove all associated subscriptions and cache entries. The mapping must be deactivated before deletion.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**| The unique identifier of the mapping to delete | [default to null] |

### Return type

**String**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="deleteVersion"></a>
# **deleteVersion**
> deleteVersion(id, version)

Delete a version of a mapping

    Deletes an inactive published version. The active version cannot be deleted.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **version** | **String**|  | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getDraft"></a>
# **getDraft**
> Mapping getDraft(id)

Get the draft (working copy) of a mapping

    Returns the unpublished draft for a mapping line, if one exists. Editing a mapping saves to this draft and never changes the running/active configuration. Returns 204 when there is no draft.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

[basicAuth](../README.md#basicAuth)

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

[basicAuth](../README.md#basicAuth)

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

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getVersion"></a>
# **getVersion**
> MappingVersion getVersion(id, version)

Get a specific version of a mapping

    Returns the full configuration of a single published version.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **version** | **String**|  | [default to null] |

### Return type

[**MappingVersion**](../Models/MappingVersion.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getVersionCounts"></a>
# **getVersionCounts**
> List getVersionCounts(direction)

Get published version counts for all mappings

    Returns the number of published versions per mapping in a single inventory scan. Optionally filter by direction.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **direction** | **String**| Filter mappings by direction | [optional] [default to null] [enum: INBOUND, OUTBOUND, UNSPECIFIED] |

### Return type

[**List**](../Models/MappingVersionCount.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getVersions"></a>
# **getVersions**
> List getVersions(id)

List versions of a mapping

    Returns all published versions of a mapping line. The active version is the one whose &#x60;version&#x60; field matches the mapping&#39;s current &#x60;version&#x60;.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

[**List**](../Models/MappingVersion.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="publishDraft"></a>
# **publishDraft**
> MappingVersion publishDraft(id, version, note)

Publish the draft as a new version

    Freezes the mapping line&#39;s current draft into a new immutable version and clears the draft. The currently active configuration is captured as a version first if the line has none yet. This does not activate the new version; activate it separately via the ACTIVATE_MAPPING operation.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **version** | **String**|  | [default to null] |
| **note** | **String**|  | [optional] [default to null] |

### Return type

[**MappingVersion**](../Models/MappingVersion.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="saveDraft"></a>
# **saveDraft**
> Mapping saveDraft(id, Mapping)

Save edits into the draft of a mapping

    Saves the supplied configuration into the mapping line&#39;s draft (working copy) without changing the running/active configuration. To apply a draft, publish it as a version and activate that version.  Optimistic concurrency: include the draft&#39;s last &#x60;lastUpdate&#x60; value in the body; if the stored draft has changed since then the request is rejected with 409.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **Mapping** | [**Mapping**](../Models/Mapping.md)|  | |

### Return type

[**Mapping**](../Models/Mapping.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="suggestNextVersions"></a>
# **suggestNextVersions**
> Map suggestNextVersions(id)

Suggest the next semver labels for a mapping

    Returns three semver suggestions — patch, minor, and major bumps — based on the highest published version for the mapping line. Use these to pre-fill the publish dialog.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

[basicAuth](../README.md#basicAuth)

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

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateVersionNote"></a>
# **updateVersionNote**
> MappingVersion updateVersionNote(id, version, note)

Update a version&#39;s note

    Updates the change note of a published version. The note is the only mutable field of a version; all other fields are immutable.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **id** | **String**|  | [default to null] |
| **version** | **String**|  | [default to null] |
| **note** | **String**|  | [optional] [default to null] |

### Return type

[**MappingVersion**](../Models/MappingVersion.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

