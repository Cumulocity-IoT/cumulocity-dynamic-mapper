# CacheControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getCacheSize**](CacheControllerApi.md#getCacheSize) | **GET** /cache | Get cache size |


<a name="getCacheSize"></a>
# **getCacheSize**
> Integer getCacheSize(cacheId)

Get cache size

    Returns the current number of entries in the specified cache.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **cacheId** | **String**| Identifier of the cache. Supported values: INVENTORY_CACHE, INBOUND_ID_CACHE | [default to null] [enum: INVENTORY_CACHE, INBOUND_ID_CACHE] |

### Return type

**Integer**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

