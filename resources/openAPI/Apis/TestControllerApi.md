# TestControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**echoHealth**](TestControllerApi.md#echoHealth) | **GET** /webhook |  |
| [**echoInput**](TestControllerApi.md#echoInput) | **POST** /webhook/echo/** |  |
| [**forwardPayload**](TestControllerApi.md#forwardPayload) | **POST** /test/{method} |  |


<a name="echoHealth"></a>
# **echoHealth**
> String echoHealth()



### Parameters
This endpoint does not need any parameter.

### Return type

**String**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: */*

<a name="echoInput"></a>
# **echoInput**
> String echoInput(body)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **body** | **String**|  | |

### Return type

**String**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: */*

<a name="forwardPayload"></a>
# **forwardPayload**
> List forwardPayload(method, topic, connectorIdentifier, request\_body)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **method** | **String**|  | [default to null] |
| **topic** | **URI**|  | [default to null] |
| **connectorIdentifier** | **String**|  | [default to null] |
| **request\_body** | [**Map**](../Models/object.md)|  | |

### Return type

[**List**](../Models/ProcessingContextObject.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

