# DeviceSubscriptionControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**subscriptionCreate**](DeviceSubscriptionControllerApi.md#subscriptionCreate) | **POST** /subscription |  |
| [**subscriptionDelete**](DeviceSubscriptionControllerApi.md#subscriptionDelete) | **DELETE** /subscription/{deviceId} |  |
| [**subscriptionUpdate**](DeviceSubscriptionControllerApi.md#subscriptionUpdate) | **PUT** /subscription |  |
| [**subscriptionsGet**](DeviceSubscriptionControllerApi.md#subscriptionsGet) | **GET** /subscription |  |


<a name="subscriptionCreate"></a>
# **subscriptionCreate**
> C8YNotificationSubscription subscriptionCreate(C8YNotificationSubscription)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **C8YNotificationSubscription** | [**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)|  | |

### Return type

[**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="subscriptionDelete"></a>
# **subscriptionDelete**
> Object subscriptionDelete(deviceId)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**|  | [default to null] |

### Return type

**Object**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="subscriptionUpdate"></a>
# **subscriptionUpdate**
> C8YNotificationSubscription subscriptionUpdate(C8YNotificationSubscription)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **C8YNotificationSubscription** | [**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)|  | |

### Return type

[**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="subscriptionsGet"></a>
# **subscriptionsGet**
> C8YNotificationSubscription subscriptionsGet(deviceId, subscriptionName)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**|  | [optional] [default to null] |
| **subscriptionName** | **String**|  | [optional] [default to null] |

### Return type

[**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

