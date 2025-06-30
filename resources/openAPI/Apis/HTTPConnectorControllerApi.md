# HTTPConnectorControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**processGenericMessage**](HTTPConnectorControllerApi.md#processGenericMessage) | **POST** /httpConnector | Process HTTP connector message |
| [**processGenericMessage1**](HTTPConnectorControllerApi.md#processGenericMessage1) | **PUT** /httpConnector | Process HTTP connector message |
| [**processGenericMessage2**](HTTPConnectorControllerApi.md#processGenericMessage2) | **POST** /httpConnector/** | Process HTTP connector message |
| [**processGenericMessage3**](HTTPConnectorControllerApi.md#processGenericMessage3) | **PUT** /httpConnector/** | Process HTTP connector message |


<a name="processGenericMessage"></a>
# **processGenericMessage**
> processGenericMessage(path)

Process HTTP connector message

    Receives HTTP messages from external systems and processes them through the dynamic mapping system. This endpoint acts as a webhook receiver that can handle various payload formats (JSON, XML, plain text, binary). The path after &#39;/httpConnector&#39; is used as the topic for mapping resolution.  **Path Examples:** - POST /httpConnector/sensors/temperature → topic: &#39;sensors/temperature&#39; - PUT /httpConnector/devices/device001/data → topic: &#39;devices/device001/data&#39; - POST /httpConnector → topic: &#39;&#39; (empty, root level)  **Security:** Requires ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **path** | **String**| Dynamic path that becomes the topic for mapping resolution. Everything after &#39;/httpConnector&#39; is used as the topic. | [optional] [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json, application/xml, text/plain, application/octet-stream
- **Accept**: application/json

<a name="processGenericMessage1"></a>
# **processGenericMessage1**
> processGenericMessage1(path)

Process HTTP connector message

    Receives HTTP messages from external systems and processes them through the dynamic mapping system. This endpoint acts as a webhook receiver that can handle various payload formats (JSON, XML, plain text, binary). The path after &#39;/httpConnector&#39; is used as the topic for mapping resolution.  **Path Examples:** - POST /httpConnector/sensors/temperature → topic: &#39;sensors/temperature&#39; - PUT /httpConnector/devices/device001/data → topic: &#39;devices/device001/data&#39; - POST /httpConnector → topic: &#39;&#39; (empty, root level)  **Security:** Requires ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **path** | **String**| Dynamic path that becomes the topic for mapping resolution. Everything after &#39;/httpConnector&#39; is used as the topic. | [optional] [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json, application/xml, text/plain, application/octet-stream
- **Accept**: application/json

<a name="processGenericMessage2"></a>
# **processGenericMessage2**
> processGenericMessage2(path)

Process HTTP connector message

    Receives HTTP messages from external systems and processes them through the dynamic mapping system. This endpoint acts as a webhook receiver that can handle various payload formats (JSON, XML, plain text, binary). The path after &#39;/httpConnector&#39; is used as the topic for mapping resolution.  **Path Examples:** - POST /httpConnector/sensors/temperature → topic: &#39;sensors/temperature&#39; - PUT /httpConnector/devices/device001/data → topic: &#39;devices/device001/data&#39; - POST /httpConnector → topic: &#39;&#39; (empty, root level)  **Security:** Requires ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **path** | **String**| Dynamic path that becomes the topic for mapping resolution. Everything after &#39;/httpConnector&#39; is used as the topic. | [optional] [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json, application/xml, text/plain, application/octet-stream
- **Accept**: application/json

<a name="processGenericMessage3"></a>
# **processGenericMessage3**
> processGenericMessage3(path)

Process HTTP connector message

    Receives HTTP messages from external systems and processes them through the dynamic mapping system. This endpoint acts as a webhook receiver that can handle various payload formats (JSON, XML, plain text, binary). The path after &#39;/httpConnector&#39; is used as the topic for mapping resolution.  **Path Examples:** - POST /httpConnector/sensors/temperature → topic: &#39;sensors/temperature&#39; - PUT /httpConnector/devices/device001/data → topic: &#39;devices/device001/data&#39; - POST /httpConnector → topic: &#39;&#39; (empty, root level)  **Security:** Requires ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **path** | **String**| Dynamic path that becomes the topic for mapping resolution. Everything after &#39;/httpConnector&#39; is used as the topic. | [optional] [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json, application/xml, text/plain, application/octet-stream
- **Accept**: application/json

