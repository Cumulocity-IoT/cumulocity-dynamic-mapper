# CacheControllerApi

All URIs are relative to */service/dynamic-mapper-service*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getCacheSize**](CacheControllerApi.md#getCacheSize) | **GET** /cache | Get cache size |


<a name="getCacheSize"></a>
# **getCacheSize**
> Integer getCacheSize(cacheId)

Get cache size

    Returns the current number of entries in the specified cache. Supported values for cacheId: INVENTORY_CACHE, INBOUND_ID_CACHE, OUTBOUND_ID_CACHE.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **cacheId** | **String**| Identifier of the cache to query. Allowed values: INVENTORY_CACHE, INBOUND_ID_CACHE, OUTBOUND_ID_CACHE | [default to null] |

### Return type

**Integer**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, */*

