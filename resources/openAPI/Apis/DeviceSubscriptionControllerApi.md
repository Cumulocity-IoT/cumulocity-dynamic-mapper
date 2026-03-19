# DeviceSubscriptionControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deleteGroupSubscription**](DeviceSubscriptionControllerApi.md#deleteGroupSubscription) | **DELETE** /subscription/group/{groupId} | Delete device group notification subscription |
| [**getGroupSubscriptions**](DeviceSubscriptionControllerApi.md#getGroupSubscriptions) | **GET** /subscription/group | Get group notification subscriptions |
| [**getTypeSubscriptions**](DeviceSubscriptionControllerApi.md#getTypeSubscriptions) | **GET** /subscription/type | Get device type notification subscriptions |
| [**subscriptionCreate**](DeviceSubscriptionControllerApi.md#subscriptionCreate) | **POST** /subscription | Create device notification subscription |
| [**subscriptionDelete**](DeviceSubscriptionControllerApi.md#subscriptionDelete) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
| [**subscriptionUpdate**](DeviceSubscriptionControllerApi.md#subscriptionUpdate) | **PUT** /subscription | Update device notification subscription |
| [**subscriptionsGet**](DeviceSubscriptionControllerApi.md#subscriptionsGet) | **GET** /subscription | Get device notification subscriptions |
| [**updateGroupSubscription**](DeviceSubscriptionControllerApi.md#updateGroupSubscription) | **PUT** /subscription/group | Update group notification subscription |
| [**updateTypeSubscription**](DeviceSubscriptionControllerApi.md#updateTypeSubscription) | **PUT** /subscription/type | Update device type notification subscription |


<a name="deleteGroupSubscription"></a>
# **deleteGroupSubscription**
> deleteGroupSubscription(groupId)

Delete device group notification subscription

    Removes notification subscription for a specific device group.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **groupId** | **String**| ID of the device group to unsubscribe | [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: Not defined

<a name="getGroupSubscriptions"></a>
# **getGroupSubscriptions**
> NotificationSubscriptionResponse getGroupSubscriptions()

Get group notification subscriptions

    Retrieves current notification subscriptions for device groups.

### Parameters
This endpoint does not need any parameter.

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getTypeSubscriptions"></a>
# **getTypeSubscriptions**
> NotificationSubscriptionResponse getTypeSubscriptions()

Get device type notification subscriptions

    Retrieves current notification subscriptions for device types.

### Parameters
This endpoint does not need any parameter.

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="subscriptionCreate"></a>
# **subscriptionCreate**
> NotificationSubscriptionResponse subscriptionCreate(NotificationSubscriptionRequest)

Create device notification subscription

    Creates notification subscriptions for specified devices to enable outbound mapping functionality. When devices are subscribed, the system will receive real-time notifications about changes to the devices and can trigger outbound mappings accordingly.  **Prerequisites:** - Outbound mapping must be enabled in service configuration - User must have CREATE or ADMIN role  **Behavior:** - Automatically discovers and includes all child devices - Creates subscriptions for all specified API types - Returns the subscription with all included devices  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)| Subscription configuration with devices and API types | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="subscriptionDelete"></a>
# **subscriptionDelete**
> subscriptionDelete(deviceId)

Delete device notification subscription

    Removes notification subscription for a specific device. The device will no longer trigger outbound mappings when its data changes.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**| ID of the device to unsubscribe | [default to null] |

### Return type

null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="subscriptionUpdate"></a>
# **subscriptionUpdate**
> NotificationSubscriptionResponse subscriptionUpdate(NotificationSubscriptionRequest)

Update device notification subscription

    Updates an existing notification subscription by comparing the new device list with the current subscriptions.  **Update Logic:** 1. Devices in new list but not in current subscriptions → Subscribe 2. Devices in current subscriptions but not in new list → Unsubscribe 3. Devices in both lists → No change  **Prerequisites:** - Outbound mapping must be enabled in service configuration  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)| Updated subscription configuration | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="subscriptionsGet"></a>
# **subscriptionsGet**
> NotificationSubscriptionResponse subscriptionsGet(subscription)

Get device notification subscriptions

    Retrieves current notification subscriptions, optionally filtered by device ID or subscription name. Shows which devices are currently subscribed for outbound notifications. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **subscription** | **String**| Name of the subscription | [default to null] |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="updateGroupSubscription"></a>
# **updateGroupSubscription**
> NotificationSubscriptionResponse updateGroupSubscription(NotificationSubscriptionRequest)

Update group notification subscription

    Updates the notification subscription for a device group.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

<a name="updateTypeSubscription"></a>
# **updateTypeSubscription**
> NotificationSubscriptionResponse updateTypeSubscription(NotificationSubscriptionRequest)

Update device type notification subscription

    Updates the notification subscription for device types.  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **NotificationSubscriptionRequest** | [**NotificationSubscriptionRequest**](../Models/NotificationSubscriptionRequest.md)|  | |

### Return type

[**NotificationSubscriptionResponse**](../Models/NotificationSubscriptionResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json

