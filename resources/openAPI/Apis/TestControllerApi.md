# TestControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**echoHealth**](TestControllerApi.md#echoHealth) | **GET** /webhook | Webhook health check |
| [**echoInput**](TestControllerApi.md#echoInput) | **POST** /webhook/echo/** | Echo webhook input |
| [**testMapping**](TestControllerApi.md#testMapping) | **POST** /test/mapping | Test a mapping |


<a name="echoHealth"></a>
# **echoHealth**
> String echoHealth()

Webhook health check

    Returns 200 OK to confirm the webhook echo endpoint is reachable.

### Parameters
This endpoint does not need any parameter.

### Return type

**String**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: */*

<a name="echoInput"></a>
# **echoInput**
> echoInput(body)

Echo webhook input

    Accepts any POST request and returns the body unchanged. Useful for testing outbound webhook mappings.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **body** | **String**|  | |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="testMapping"></a>
# **testMapping**
> TestResult testMapping(TestContext)

Test a mapping

    Executes a mapping transformation against a provided payload and returns the generated requests and any warnings or errors. Optionally sends the result to Cumulocity IoT.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **TestContext** | [**TestContext**](../Models/TestContext.md)|  | |

### Return type

[**TestResult**](../Models/TestResult.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

