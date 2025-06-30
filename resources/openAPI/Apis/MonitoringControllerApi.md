# MonitoringControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getActiveSubscriptions**](MonitoringControllerApi.md#getActiveSubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} | Get active subscriptions for connector |
| [**getConnectorStatus**](MonitoringControllerApi.md#getConnectorStatus) | **GET** /monitoring/status/connector/{connectorIdentifier} | Get connector status |
| [**getConnectorsStatus**](MonitoringControllerApi.md#getConnectorsStatus) | **GET** /monitoring/status/connectors | Get all connectors status |
| [**getInboundMappingTree**](MonitoringControllerApi.md#getInboundMappingTree) | **GET** /monitoring/tree | Get inbound mapping tree |
| [**getMappingStatus**](MonitoringControllerApi.md#getMappingStatus) | **GET** /monitoring/status/mapping/statistic | Get mapping statistics |


<a name="getActiveSubscriptions"></a>
# **getActiveSubscriptions**
> Object getActiveSubscriptions(connectorIdentifier)

Get active subscriptions for connector

    Retrieves the active topic subscriptions for a specific connector with the count of mappings per topic. This helps monitor which topics are being subscribed to and how many mappings are listening to each topic.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **connectorIdentifier** | **String**| The unique identifier of the connector | [default to null] |

### Return type

**Object**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorStatus"></a>
# **getConnectorStatus**
> ConnectorStatusEvent getConnectorStatus(connectorIdentifier)

Get connector status

    Retrieves the current status of a specific connector including connection state, last update time, and any error messages.

### Parameters

|Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **connectorIdentifier** | **String**| The unique identifier of the connector | [default to null] |

### Return type

[**ConnectorStatusEvent**](../Models/ConnectorStatusEvent.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getConnectorsStatus"></a>
# **getConnectorsStatus**
> Object getConnectorsStatus()

Get all connectors status

    Retrieves the status of all connectors for the current tenant. Returns a map with connector identifiers as keys and their status information as values. Includes both enabled and disabled connectors.

### Parameters
This endpoint does not need any parameter.

### Return type

**Object**

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

<a name="getInboundMappingTree"></a>
# **getInboundMappingTree**
> MappingTreeNode getInboundMappingTree()

Get inbound mapping tree

    Retrieves the hierarchical tree structure of inbound mappings organized by topic patterns. This shows how incoming messages are routed to different mappings based on topic matching.

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
> MappingStatus getMappingStatus()

Get mapping statistics

    Retrieves statistics for all mappings including message counts, error counts, snooping status, and loading errors. Useful for monitoring mapping performance and health.

### Parameters
This endpoint does not need any parameter.

### Return type

[**MappingStatus**](../Models/AnyType.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json

