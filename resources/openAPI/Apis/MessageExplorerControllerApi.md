# MessageExplorerControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**clearMessages**](MessageExplorerControllerApi.md#clearMessages) | **DELETE** /explorer/session/{sessionId}/messages | Clear buffered messages |
| [**getMessages**](MessageExplorerControllerApi.md#getMessages) | **GET** /explorer/session/{sessionId}/messages | Poll buffered messages |
| [**startSession**](MessageExplorerControllerApi.md#startSession) | **POST** /explorer/session | Start an explorer session |
| [**stopSession**](MessageExplorerControllerApi.md#stopSession) | **DELETE** /explorer/session/{sessionId} | Stop an explorer session |


<a name="clearMessages"></a>
# **clearMessages**
> clearMessages(sessionId)

Clear buffered messages

    Discards all captured messages in the session buffer without stopping the session.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **sessionId** | **String**| Session ID | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getMessages"></a>
# **getMessages**
> List getMessages(sessionId)

Poll buffered messages

    Returns all messages captured since the session started (or since the last clear). Updates the session TTL.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **sessionId** | **String**| Session ID | [default to null] |

### Return type

[**List**](../Models/ExplorerMessage.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="startSession"></a>
# **startSession**
> Object startSession(StartSessionRequest)

Start an explorer session

    Creates a new session that captures raw inbound messages from the specified connector and topic.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **StartSessionRequest** | [**StartSessionRequest**](../Models/StartSessionRequest.md)|  | |

### Return type

**Object**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="stopSession"></a>
# **stopSession**
> stopSession(sessionId)

Stop an explorer session

    Terminates the session and unregisters its listener from the connector.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **sessionId** | **String**| Session ID returned by POST /explorer/session | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

