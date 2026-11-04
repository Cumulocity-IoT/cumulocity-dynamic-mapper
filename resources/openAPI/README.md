# Documentation for Dynamic Mapper API

<a name="documentation-for-api-endpoints"></a>
## Documentation for API Endpoints

All URIs are relative to */service/dynamic-mapper-service*

| Class | Method | HTTP request | Description |
|------------ | ------------- | ------------- | -------------|
| *AgentDefinitionControllerApi* | [**getJsonataAgent**](Apis/AgentDefinitionControllerApi.md#getJsonataAgent) | **GET** /agents/jsonata_agent.json |  |
*AgentDefinitionControllerApi* | [**getSmartFunctionAgent**](Apis/AgentDefinitionControllerApi.md#getSmartFunctionAgent) | **GET** /agents/smartfunction_agent.json |  |
| *CacheControllerApi* | [**getCacheSize**](Apis/CacheControllerApi.md#getCacheSize) | **GET** /cache | Get cache size |
| *ClientRelationControllerApi* | [**addOrUpdateClientRelations**](Apis/ClientRelationControllerApi.md#addOrUpdateClientRelations) | **PUT** /relation/client/{clientId} | Update client relation for client |
*ClientRelationControllerApi* | [**clearAllClientRelations**](Apis/ClientRelationControllerApi.md#clearAllClientRelations) | **DELETE** /relation/client | Clear all client relations |
*ClientRelationControllerApi* | [**getAllClientRelations**](Apis/ClientRelationControllerApi.md#getAllClientRelations) | **GET** /relation/client | Get all client relations |
*ClientRelationControllerApi* | [**getAllClients**](Apis/ClientRelationControllerApi.md#getAllClients) | **GET** /relation/clients | Get all clients |
*ClientRelationControllerApi* | [**getClientForDevice**](Apis/ClientRelationControllerApi.md#getClientForDevice) | **GET** /relation/device/{deviceId}/client | Get client mapping for device |
*ClientRelationControllerApi* | [**getDevicesForClient**](Apis/ClientRelationControllerApi.md#getDevicesForClient) | **GET** /relation/client/{clientId}/devices | Get all devices mapped to a specific client |
*ClientRelationControllerApi* | [**removeAllRelationsForClient**](Apis/ClientRelationControllerApi.md#removeAllRelationsForClient) | **DELETE** /relation/client/{clientId} | Remove all device mappings for a specific client |
*ClientRelationControllerApi* | [**removeRelationForDevice**](Apis/ClientRelationControllerApi.md#removeRelationForDevice) | **DELETE** /relation/device/{deviceId} | Remove client mapping for specific device |
| *ConfigurationManagementApi* | [**createCodeTemplate**](Apis/ConfigurationManagementApi.md#createCodeTemplate) | **POST** /configuration/code | Create code template |
*ConfigurationManagementApi* | [**createConnectorConfiguration**](Apis/ConfigurationManagementApi.md#createConnectorConfiguration) | **POST** /configuration/connector/instance | Create connector configuration |
*ConfigurationManagementApi* | [**deleteCodeTemplate**](Apis/ConfigurationManagementApi.md#deleteCodeTemplate) | **DELETE** /configuration/code/{id} | Delete code template |
*ConfigurationManagementApi* | [**deleteConnectionConfiguration**](Apis/ConfigurationManagementApi.md#deleteConnectionConfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete connector configuration |
*ConfigurationManagementApi* | [**getCodeTemplate**](Apis/ConfigurationManagementApi.md#getCodeTemplate) | **GET** /configuration/code/{id} | Get code template |
*ConfigurationManagementApi* | [**getCodeTemplates**](Apis/ConfigurationManagementApi.md#getCodeTemplates) | **GET** /configuration/code | Get all code templates |
*ConfigurationManagementApi* | [**getConnectionConfiguration**](Apis/ConfigurationManagementApi.md#getConnectionConfiguration) | **GET** /configuration/connector/instance/{identifier} | Get connector configuration |
*ConfigurationManagementApi* | [**getConnectionConfigurations**](Apis/ConfigurationManagementApi.md#getConnectionConfigurations) | **GET** /configuration/connector/instance | Get connector configurations |
*ConfigurationManagementApi* | [**getConnectorSpecifications**](Apis/ConfigurationManagementApi.md#getConnectorSpecifications) | **GET** /configuration/connector/specifications | Get connector specifications |
*ConfigurationManagementApi* | [**getFeatures**](Apis/ConfigurationManagementApi.md#getFeatures) | **GET** /configuration/feature | Get feature flags |
*ConfigurationManagementApi* | [**getServiceConfiguration**](Apis/ConfigurationManagementApi.md#getServiceConfiguration) | **GET** /configuration/service | Get service configuration |
*ConfigurationManagementApi* | [**testConnectionConfiguration**](Apis/ConfigurationManagementApi.md#testConnectionConfiguration) | **POST** /configuration/connector/test | Test connector configuration |
*ConfigurationManagementApi* | [**updateCodeTemplate**](Apis/ConfigurationManagementApi.md#updateCodeTemplate) | **PUT** /configuration/code/{id} | Update code template |
*ConfigurationManagementApi* | [**updateConnectionConfiguration**](Apis/ConfigurationManagementApi.md#updateConnectionConfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update connector configuration |
*ConfigurationManagementApi* | [**updateServiceConfiguration**](Apis/ConfigurationManagementApi.md#updateServiceConfiguration) | **PUT** /configuration/service | Update service configuration |
| *DeploymentControllerApi* | [**getDeploymentMap**](Apis/DeploymentControllerApi.md#getDeploymentMap) | **GET** /deployment/defined | Get complete deployment configuration |
*DeploymentControllerApi* | [**getDeploymentMapEntry**](Apis/DeploymentControllerApi.md#getDeploymentMapEntry) | **GET** /deployment/defined/{mappingIdentifier} | Get deployment configuration for mapping |
*DeploymentControllerApi* | [**getMappingsDeployed**](Apis/DeploymentControllerApi.md#getMappingsDeployed) | **GET** /deployment/effective | Get effective deployments |
*DeploymentControllerApi* | [**updateDeploymentMapEntry**](Apis/DeploymentControllerApi.md#updateDeploymentMapEntry) | **PUT** /deployment/defined/{mappingIdentifier} | Update deployment configuration for mapping |
| *DeviceSubscriptionControllerApi* | [**createSubscription**](Apis/DeviceSubscriptionControllerApi.md#createSubscription) | **POST** /subscription | Create device notification subscription |
*DeviceSubscriptionControllerApi* | [**deleteGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#deleteGroupSubscription) | **DELETE** /subscription/group/{groupId} | Delete device group notification subscription |
*DeviceSubscriptionControllerApi* | [**deleteSubscription**](Apis/DeviceSubscriptionControllerApi.md#deleteSubscription) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
*DeviceSubscriptionControllerApi* | [**getGroupSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getGroupSubscriptions) | **GET** /subscription/group | Get group notification subscriptions |
*DeviceSubscriptionControllerApi* | [**getSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getSubscriptions) | **GET** /subscription | Get device notification subscriptions |
*DeviceSubscriptionControllerApi* | [**getTypeSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getTypeSubscriptions) | **GET** /subscription/type | Get device type notification subscriptions |
*DeviceSubscriptionControllerApi* | [**resyncTypeSubscription**](Apis/DeviceSubscriptionControllerApi.md#resyncTypeSubscription) | **POST** /subscription/type/resync/{type} | Resync existing devices into a type subscription |
*DeviceSubscriptionControllerApi* | [**resyncTypeSubscriptionByParam**](Apis/DeviceSubscriptionControllerApi.md#resyncTypeSubscriptionByParam) | **POST** /subscription/type/resync | Resync existing devices into a type subscription (type as query parameter) |
*DeviceSubscriptionControllerApi* | [**updateGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#updateGroupSubscription) | **PUT** /subscription/group | Update group notification subscription (desired state) |
*DeviceSubscriptionControllerApi* | [**updateSubscription**](Apis/DeviceSubscriptionControllerApi.md#updateSubscription) | **PUT** /subscription | Update device notification subscription |
*DeviceSubscriptionControllerApi* | [**updateTypeSubscription**](Apis/DeviceSubscriptionControllerApi.md#updateTypeSubscription) | **PUT** /subscription/type | Update device type notification subscription |
| *ExtensionControllerApi* | [**deleteProcessorExtension**](Apis/ExtensionControllerApi.md#deleteProcessorExtension) | **DELETE** /extension/{extensionName} | Delete a processor extension |
*ExtensionControllerApi* | [**getProcessorExtension**](Apis/ExtensionControllerApi.md#getProcessorExtension) | **GET** /extension/{extensionName} | Get a specific processor extension |
*ExtensionControllerApi* | [**getProcessorExtensions**](Apis/ExtensionControllerApi.md#getProcessorExtensions) | **GET** /extension | Get all processor extensions |
| *HTTPConnectorControllerApi* | [**processGenericMessage**](Apis/HTTPConnectorControllerApi.md#processGenericMessage) | **POST** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage1**](Apis/HTTPConnectorControllerApi.md#processGenericMessage1) | **PUT** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage2**](Apis/HTTPConnectorControllerApi.md#processGenericMessage2) | **POST** /httpConnector/** | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage3**](Apis/HTTPConnectorControllerApi.md#processGenericMessage3) | **PUT** /httpConnector/** | Process HTTP connector message |
| *MappingControllerApi* | [**createMapping**](Apis/MappingControllerApi.md#createMapping) | **POST** /mapping | Create a new mapping |
*MappingControllerApi* | [**deleteDraft**](Apis/MappingControllerApi.md#deleteDraft) | **DELETE** /mapping/{id}/draft | Discard the draft of a mapping |
*MappingControllerApi* | [**deleteMapping**](Apis/MappingControllerApi.md#deleteMapping) | **DELETE** /mapping/{id} | Delete a mapping |
*MappingControllerApi* | [**deleteVersion**](Apis/MappingControllerApi.md#deleteVersion) | **DELETE** /mapping/{id}/version/{version} | Delete a version of a mapping |
*MappingControllerApi* | [**getDraft**](Apis/MappingControllerApi.md#getDraft) | **GET** /mapping/{id}/draft | Get the draft (working copy) of a mapping |
*MappingControllerApi* | [**getMapping**](Apis/MappingControllerApi.md#getMapping) | **GET** /mapping/{id} | Get a specific mapping |
*MappingControllerApi* | [**getMappings**](Apis/MappingControllerApi.md#getMappings) | **GET** /mapping | Get all mappings |
*MappingControllerApi* | [**getVersion**](Apis/MappingControllerApi.md#getVersion) | **GET** /mapping/{id}/version/{version} | Get a specific version of a mapping |
*MappingControllerApi* | [**getVersionCounts**](Apis/MappingControllerApi.md#getVersionCounts) | **GET** /mapping/version-counts | Get published version counts for all mappings |
*MappingControllerApi* | [**getVersions**](Apis/MappingControllerApi.md#getVersions) | **GET** /mapping/{id}/version | List versions of a mapping |
*MappingControllerApi* | [**publishDraft**](Apis/MappingControllerApi.md#publishDraft) | **POST** /mapping/{id}/publish | Publish the draft as a new version |
*MappingControllerApi* | [**saveDraft**](Apis/MappingControllerApi.md#saveDraft) | **PUT** /mapping/{id}/draft | Save edits into the draft of a mapping |
*MappingControllerApi* | [**suggestNextVersions**](Apis/MappingControllerApi.md#suggestNextVersions) | **GET** /mapping/{id}/version/suggest | Suggest the next semver labels for a mapping |
*MappingControllerApi* | [**updateMapping**](Apis/MappingControllerApi.md#updateMapping) | **PUT** /mapping/{id} | Update an existing mapping |
*MappingControllerApi* | [**updateVersionNote**](Apis/MappingControllerApi.md#updateVersionNote) | **PATCH** /mapping/{id}/version/{version} | Update a version's note |
| *MessageExplorerControllerApi* | [**clearMessages**](Apis/MessageExplorerControllerApi.md#clearMessages) | **DELETE** /explorer/session/{sessionId}/messages | Clear buffered messages |
*MessageExplorerControllerApi* | [**getMessages**](Apis/MessageExplorerControllerApi.md#getMessages) | **GET** /explorer/session/{sessionId}/messages | Poll buffered messages |
*MessageExplorerControllerApi* | [**startSession**](Apis/MessageExplorerControllerApi.md#startSession) | **POST** /explorer/session | Start an explorer session |
*MessageExplorerControllerApi* | [**stopSession**](Apis/MessageExplorerControllerApi.md#stopSession) | **DELETE** /explorer/session/{sessionId} | Stop an explorer session |
| *MonitoringControllerApi* | [**getActiveSubscriptions**](Apis/MonitoringControllerApi.md#getActiveSubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} | Get active subscriptions for connector |
*MonitoringControllerApi* | [**getConnectorStatus**](Apis/MonitoringControllerApi.md#getConnectorStatus) | **GET** /monitoring/status/connector/{connectorIdentifier} | Get connector status |
*MonitoringControllerApi* | [**getConnectorsStatus**](Apis/MonitoringControllerApi.md#getConnectorsStatus) | **GET** /monitoring/status/connectors | Get all connectors status |
*MonitoringControllerApi* | [**getInboundMappingTree**](Apis/MonitoringControllerApi.md#getInboundMappingTree) | **GET** /monitoring/tree | Get inbound mapping tree |
*MonitoringControllerApi* | [**getMappingStatus**](Apis/MonitoringControllerApi.md#getMappingStatus) | **GET** /monitoring/status/mapping/statistic | Get mapping statistics |
| *OperationControllerApi* | [**runOperation**](Apis/OperationControllerApi.md#runOperation) | **POST** /operation | Execute a service operation |
| *ServiceLogsControllerApi* | [**flush**](Apis/ServiceLogsControllerApi.md#flush) | **POST** /monitoring/service-logs/flush | Store the calling tenant's waiting log lines now |
*ServiceLogsControllerApi* | [**read**](Apis/ServiceLogsControllerApi.md#read) | **GET** /monitoring/service-logs | Read the collected service log |
| *TestControllerApi* | [**echoHealth**](Apis/TestControllerApi.md#echoHealth) | **GET** /webhook | Webhook health check |
*TestControllerApi* | [**echoInput**](Apis/TestControllerApi.md#echoInput) | **POST** /webhook/echo/** | Echo webhook input |
*TestControllerApi* | [**testMapping**](Apis/TestControllerApi.md#testMapping) | **POST** /test/mapping | Test a mapping |


<a name="documentation-for-models"></a>
## Documentation for Models

 - [CodeTemplate](./Models/CodeTemplate.md)
 - [ConnectionTestResult](./Models/ConnectionTestResult.md)
 - [ConnectorConfiguration](./Models/ConnectorConfiguration.md)
 - [ConnectorProperty](./Models/ConnectorProperty.md)
 - [ConnectorPropertyCondition](./Models/ConnectorPropertyCondition.md)
 - [ConnectorSpecification](./Models/ConnectorSpecification.md)
 - [ConnectorStatusEvent](./Models/ConnectorStatusEvent.md)
 - [Device](./Models/Device.md)
 - [DynamicMapperRequest](./Models/DynamicMapperRequest.md)
 - [ExplorerMessage](./Models/ExplorerMessage.md)
 - [Extension](./Models/Extension.md)
 - [ExtensionEntry](./Models/ExtensionEntry.md)
 - [Feature](./Models/Feature.md)
 - [Mapping](./Models/Mapping.md)
 - [MappingStatus](./Models/MappingStatus.md)
 - [MappingTreeNode](./Models/MappingTreeNode.md)
 - [MappingVersion](./Models/MappingVersion.md)
 - [MappingVersionCount](./Models/MappingVersionCount.md)
 - [NotificationSubscriptionRequest](./Models/NotificationSubscriptionRequest.md)
 - [NotificationSubscriptionResponse](./Models/NotificationSubscriptionResponse.md)
 - [Paging](./Models/Paging.md)
 - [ServiceConfiguration](./Models/ServiceConfiguration.md)
 - [ServiceLogPage](./Models/ServiceLogPage.md)
 - [ServiceLogsConfiguration](./Models/ServiceLogsConfiguration.md)
 - [ServiceOperation](./Models/ServiceOperation.md)
 - [StartSessionRequest](./Models/StartSessionRequest.md)
 - [Substitution](./Models/Substitution.md)
 - [TestContext](./Models/TestContext.md)
 - [TestResult](./Models/TestResult.md)
 - [ValidationErrorResponse](./Models/ValidationErrorResponse.md)
 - [ValidationIssue](./Models/ValidationIssue.md)


<a name="documentation-for-authorization"></a>
## Documentation for Authorization

<a name="basicAuth"></a>
### basicAuth

- **Type**: HTTP basic authentication

