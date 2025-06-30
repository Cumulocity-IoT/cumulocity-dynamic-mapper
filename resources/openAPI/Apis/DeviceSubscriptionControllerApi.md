# DeviceSubscriptionControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**subscriptionCreate**](DeviceSubscriptionControllerApi.md#subscriptionCreate) | **POST** /subscription | Create device notification subscription |
| [**subscriptionDelete**](DeviceSubscriptionControllerApi.md#subscriptionDelete) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
| [**subscriptionUpdate**](DeviceSubscriptionControllerApi.md#subscriptionUpdate) | **PUT** /subscription | Update device notification subscription |
| [**subscriptionsGet**](DeviceSubscriptionControllerApi.md#subscriptionsGet) | **GET** /subscription | Get device notification subscriptions |


<a name="subscriptionCreate"></a>
# **subscriptionCreate**
> C8YNotificationSubscription subscriptionCreate(C8YNotificationSubscription)

Create device notification subscription

    Creates notification subscriptions for specified devices to enable outbound mapping functionality. When devices are subscribed, the system will receive real-time notifications about changes to the devices and can trigger outbound mappings accordingly.  **Prerequisites:** - Outbound mapping must be enabled in service configuration - User must have CREATE or ADMIN role  **Behavior:** - Automatically discovers and includes all child devices - Creates subscriptions for all specified API types - Returns the subscription with all included devices  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **C8YNotificationSubscription** | [**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)| Subscription configuration with devices and API types | |

### Return type

[**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)

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
> C8YNotificationSubscription subscriptionUpdate(C8YNotificationSubscription)

Update device notification subscription

    Updates an existing notification subscription by comparing the new device list with the current subscriptions.  **Update Logic:** 1. Devices in new list but not in current subscriptions → Subscribe 2. Devices in current subscriptions but not in new list → Unsubscribe 3. Devices in both lists → No change  **Prerequisites:** - Outbound mapping must be enabled in service configuration  **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **C8YNotificationSubscription** | [**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)| Updated subscription configuration | |

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

Get device notification subscriptions

    Retrieves current notification subscriptions, optionally filtered by device ID or subscription name. Shows which devices are currently subscribed for outbound notifications. 

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deviceId** | **String**| Filter subscriptions by specific device ID | [optional] [default to null] |
| **subscriptionName** | **String**| Filter subscriptions by subscription name | [optional] [default to null] |

### Return type

[**C8YNotificationSubscription**](../Models/C8YNotificationSubscription.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

