# MonitoringControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getActiveSubscriptions**](MonitoringControllerApi.md#getActiveSubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} |  |
| [**getConnectorStatus**](MonitoringControllerApi.md#getConnectorStatus) | **GET** /monitoring/status/connector/{connectorIdentifier} |  |
| [**getConnectorsStatus**](MonitoringControllerApi.md#getConnectorsStatus) | **GET** /monitoring/status/connectors |  |
| [**getInboundMappingTree**](MonitoringControllerApi.md#getInboundMappingTree) | **GET** /monitoring/tree |  |
| [**getMappingStatus**](MonitoringControllerApi.md#getMappingStatus) | **GET** /monitoring/status/mapping/statistic |  |


<a name="getActiveSubscriptions"></a>
# **getActiveSubscriptions**
> Map getActiveSubscriptions(connectorIdentifier)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **connectorIdentifier** | **String**|  | [default to null] |

### Return type

**Map**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorStatus"></a>
# **getConnectorStatus**
> ConnectorStatusEvent getConnectorStatus(connectorIdentifier)



### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **connectorIdentifier** | **String**|  | [default to null] |

### Return type

[**ConnectorStatusEvent**](../Models/ConnectorStatusEvent.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorsStatus"></a>
# **getConnectorsStatus**
> Map getConnectorsStatus()



### Parameters
This endpoint does not need any parameter.

### Return type

[**Map**](../Models/ConnectorStatusEvent.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getInboundMappingTree"></a>
# **getInboundMappingTree**
> MappingTreeNode getInboundMappingTree()



### Parameters
This endpoint does not need any parameter.

### Return type

[**MappingTreeNode**](../Models/MappingTreeNode.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getMappingStatus"></a>
# **getMappingStatus**
> List getMappingStatus()



### Parameters
This endpoint does not need any parameter.

### Return type

[**List**](../Models/MappingStatus.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

