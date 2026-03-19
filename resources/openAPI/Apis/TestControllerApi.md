# TestControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**echoHealth**](TestControllerApi.md#echoHealth) | **GET** /webhook |  |
| [**echoInput**](TestControllerApi.md#echoInput) | **POST** /webhook/echo/** |  |
| [**testMapping**](TestControllerApi.md#testMapping) | **POST** /test/mapping | Test a mapping |


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

<a name="testMapping"></a>
# **testMapping**
> TestResult testMapping(TestContext)

Test a mapping

    Executes a mapping test with a sample payload. Can optionally send the result to Cumulocity IoT. Supports both INBOUND and OUTBOUND mapping directions.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **TestContext** | [**TestContext**](../Models/TestContext.md)| Test context containing the mapping and payload to test | |

### Return type

[**TestResult**](../Models/TestResult.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

