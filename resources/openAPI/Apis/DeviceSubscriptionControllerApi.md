# DeviceSubscriptionControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createSubscription**](DeviceSubscriptionControllerApi.md#createSubscription) | **POST** /subscription | Create device notification subscription |
| [**deleteGroupSubscription**](DeviceSubscriptionControllerApi.md#deleteGroupSubscription) | **DELETE** /subscription/group/{groupId} | Delete device group notification subscription |
| [**deleteSubscription**](DeviceSubscriptionControllerApi.md#deleteSubscription) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
| [**getGroupSubscriptions**](DeviceSubscriptionControllerApi.md#getGroupSubscriptions) | **GET** /subscription/group | Get group notification subscriptions |
| [**getSubscriptions**](DeviceSubscriptionControllerApi.md#getSubscriptions) | **GET** /subscription | Get device notification subscriptions |
| [**getTypeSubscriptions**](DeviceSubscriptionControllerApi.md#getTypeSubscriptions) | **GET** /subscription/type | Get device type notification subscriptions |
| [**resyncTypeSubscription**](DeviceSubscriptionControllerApi.md#resyncTypeSubscription) | **POST** /subscription/type/resync/{type} | Resync existing devices into a type subscription |
| [**updateGroupSubscription**](DeviceSubscriptionControllerApi.md#updateGroupSubscription) | **PUT** /subscription/group | Update group notification subscription (desired state) |
| [**updateSubscription**](DeviceSubscriptionControllerApi.md#updateSubscription) | **PUT** /subscription | Update device notification subscription |
| [**updateTypeSubscription**](DeviceSubscriptionControllerApi.md#updateTypeSubscription) | **PUT** /subscription/type | Update device type notification subscription |


<a name="createSubscription"></a>
# **createSubscription**
> NotificationSubscriptionResponse createSubscription(NotificationSubscriptionRequest)

Create device notification subscription

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="deleteGroupSubscription"></a>
# **deleteGroupSubscription**
> Object deleteGroupSubscription(groupId)

Delete device group notification subscription

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **groupId** | **String**|  | [default to null] |

### Return type

**Object**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="deleteSubscription"></a>
# **deleteSubscription**
> Object deleteSubscription(deviceId, subscription)

Delete device notification subscription

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**|  | [default to null] |
| **subscription** | **String**|  | [default to null] |

### Return type

**Object**

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getGroupSubscriptions"></a>
# **getGroupSubscriptions**
> NotificationSubscriptionResponse getGroupSubscriptions(currentPage, pageSize, withTotalPages)

Get group notification subscriptions

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **currentPage** | **Integer**|  | [optional] [default to null] |
| **pageSize** | **Integer**|  | [optional] [default to null] |
| **withTotalPages** | **Boolean**|  | [optional] [default to true] |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getSubscriptions"></a>
# **getSubscriptions**
> NotificationSubscriptionResponse getSubscriptions(subscription, currentPage, pageSize, withTotalPages)

Get device notification subscriptions

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **subscription** | **String**|  | [default to null] |
| **currentPage** | **Integer**|  | [optional] [default to null] |
| **pageSize** | **Integer**|  | [optional] [default to null] |
| **withTotalPages** | **Boolean**|  | [optional] [default to true] |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getTypeSubscriptions"></a>
# **getTypeSubscriptions**
> NotificationSubscriptionResponse getTypeSubscriptions()

Get device type notification subscriptions

### Parameters
This endpoint does not need any parameter.

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="resyncTypeSubscription"></a>
# **resyncTypeSubscription**
> resyncTypeSubscription(type)

Resync existing devices into a type subscription

    Notification 2.0&#39;s tenant-level type filter only fires on future inventory CREATE events — devices that existed before the type was added are never picked up automatically. This scans the current inventory for the given (already-configured) type and subscribes any device not yet covered. Runs asynchronously in the background; watch the dynamic device subscription list to see it progress.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **type** | **String**|  | [default to null] |

### Return type

null (empty response body)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="updateGroupSubscription"></a>
# **updateGroupSubscription**
> NotificationSubscriptionResponse updateGroupSubscription(NotificationSubscriptionRequest)

Update group notification subscription (desired state)

    Accepts the full desired set of subscribed groups. The backend computes the diff against the current state and applies the necessary additions and removals. Sending [A, B] always results in exactly groups A and B being subscribed — nothing more, nothing less.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateSubscription"></a>
# **updateSubscription**
> NotificationSubscriptionResponse updateSubscription(NotificationSubscriptionRequest, subscription)

Update device notification subscription

    Updates static or dynamic device subscriptions. Pass ?subscription&#x3D;DynamicMapperDynamicDeviceSubscription to target the dynamic bucket.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |
| **subscription** | **String**|  | [optional] [default to DynamicMapperStaticDeviceSubscription] |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateTypeSubscription"></a>
# **updateTypeSubscription**
> NotificationSubscriptionResponse updateTypeSubscription(NotificationSubscriptionRequest)

Update device type notification subscription

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

[basicAuth](../README.md#basicAuth)

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

