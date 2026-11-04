# ServiceLogsControllerApi

All URIs are relative to */service/dynamic-mapper-service*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**flush**](ServiceLogsControllerApi.md#flush) | **POST** /monitoring/service-logs/flush | Store the calling tenant&#39;s waiting log lines now |
| [**read**](ServiceLogsControllerApi.md#read) | **GET** /monitoring/service-logs | Read the collected service log |


<a name="flush"></a>
# **flush**
> flush()

Store the calling tenant&#39;s waiting log lines now

    Stores the lines this service instance has captured for the calling tenant but not yet written, instead of waiting for the collection interval, so a following read returns them. Only the instance that handles the request is flushed. Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60;.

### Parameters
This endpoint does not need any parameter.

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="read"></a>
# **read**
> Page read(dateFrom, dateTo, mapping)

Read the collected service log

    Returns a window of the calling tenant&#39;s collected log lines, in the paging shape of the platform&#39;s native log. No parameter: the latest chunks. &#x60;dateFrom&#x60; only: the chunks after it (next page; use 1970-01-01T00:00:00Z for the first). &#x60;dateTo&#x60; only: the chunks before it (previous page). &#x60;mapping&#x60; (a mapping&#39;s identifier) restricts the result to the lines written while that mapping processed a payload; lines collected before this was introduced carry no mapping and never match. Requires &#x60;ROLE_DYNAMIC_MAPPER_ADMIN&#x60;.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **dateFrom** | **String**|  | [optional] [default to null] |
| **dateTo** | **String**|  | [optional] [default to null] |
| **mapping** | **String**|  | [optional] [default to null] |

### Return type

[**Page**](../Models/Page.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

