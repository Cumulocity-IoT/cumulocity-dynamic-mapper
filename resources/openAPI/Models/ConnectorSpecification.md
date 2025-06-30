# ConnectorSpecification
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **name** | **String** | The name of the connector | [default to null] |
| **description** | **String** | A description of the connector | [default to null] |
| **connectorType** | **String** | The type of the Connector | [default to null] |
| **properties** | [**Map**](ConnectorProperty.md) | A map of properties the connector needs to establish a connection. The key is the property name and the value is the property specification | [default to null] |
| **supportsMessageContext** | **Boolean** | A flag to define if the connector supports message context. If true, the connector can handle additional metadata in messages. | [default to null] |
| **supportedDirections** | **List** | A List to define if the connector support INBOUND and OUTBOUND mappings or both. | [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

