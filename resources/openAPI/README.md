# Documentation for Dynamic Mapper API

<a name="documentation-for-api-endpoints"></a>
## Documentation for API Endpoints

All URIs are relative to *http://localhost:8080*

| Class | Method | HTTP request | Description |
|------------ | ------------- | ------------- | -------------|
| *CacheControllerApi* | [**getCacheSize**](Apis/CacheControllerApi.md#getcachesize) | **GET** /cache | Get cache size |
| *ClientRelationControllerApi* | [**addOrUpdateClientRelations**](Apis/ClientRelationControllerApi.md#addorupdateclientrelations) | **PUT** /relation/client/{clientId} | Update client relation for client |
*ClientRelationControllerApi* | [**clearAllClientRelations**](Apis/ClientRelationControllerApi.md#clearallclientrelations) | **DELETE** /relation/client | Clear all client relations |
*ClientRelationControllerApi* | [**getAllClientRelations**](Apis/ClientRelationControllerApi.md#getallclientrelations) | **GET** /relation/client | Get all client relations |
*ClientRelationControllerApi* | [**getAllClients**](Apis/ClientRelationControllerApi.md#getallclients) | **GET** /relation/clients | Get all clients |
*ClientRelationControllerApi* | [**getClientForDevice**](Apis/ClientRelationControllerApi.md#getclientfordevice) | **GET** /relation/device/{deviceId}/client | Get client mapping for device |
*ClientRelationControllerApi* | [**getDevicesForClient**](Apis/ClientRelationControllerApi.md#getdevicesforclient) | **GET** /relation/client/{clientId}/devices | Get all devices mapped to a specific client |
*ClientRelationControllerApi* | [**removeAllRelationsForClient**](Apis/ClientRelationControllerApi.md#removeallrelationsforclient) | **DELETE** /relation/client/{clientId} | Remove all device mappings for a specific client |
*ClientRelationControllerApi* | [**removeRelationForDevice**](Apis/ClientRelationControllerApi.md#removerelationfordevice) | **DELETE** /relation/device/{deviceId} | Remove client mapping for specific device |
| *ConfigurationManagementApi* | [**createCodeTemplate**](Apis/ConfigurationManagementApi.md#createcodetemplate) | **POST** /configuration/code | Create code template |
*ConfigurationManagementApi* | [**createConnectorConfiguration**](Apis/ConfigurationManagementApi.md#createconnectorconfiguration) | **POST** /configuration/connector/instance | Create connector configuration |
*ConfigurationManagementApi* | [**deleteCodeTemplate**](Apis/ConfigurationManagementApi.md#deletecodetemplate) | **DELETE** /configuration/code/{id} | Delete code template |
*ConfigurationManagementApi* | [**deleteConnectionConfiguration**](Apis/ConfigurationManagementApi.md#deleteconnectionconfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete connector configuration |
*ConfigurationManagementApi* | [**getCodeTemplate**](Apis/ConfigurationManagementApi.md#getcodetemplate) | **GET** /configuration/code/{id} | Get code template |
*ConfigurationManagementApi* | [**getCodeTemplates**](Apis/ConfigurationManagementApi.md#getcodetemplates) | **GET** /configuration/code | Get all code templates |
*ConfigurationManagementApi* | [**getConnectionConfiguration**](Apis/ConfigurationManagementApi.md#getconnectionconfiguration) | **GET** /configuration/connector/instance/{identifier} | Get connector configuration |
*ConfigurationManagementApi* | [**getConnectionConfigurations**](Apis/ConfigurationManagementApi.md#getconnectionconfigurations) | **GET** /configuration/connector/instance | Get connector configurations |
*ConfigurationManagementApi* | [**getConnectorSpecifications**](Apis/ConfigurationManagementApi.md#getconnectorspecifications) | **GET** /configuration/connector/specifications | Get connector specifications |
*ConfigurationManagementApi* | [**getFeatures**](Apis/ConfigurationManagementApi.md#getfeatures) | **GET** /configuration/feature | Get feature flags |
*ConfigurationManagementApi* | [**getServiceConfiguration**](Apis/ConfigurationManagementApi.md#getserviceconfiguration) | **GET** /configuration/service | Get service configuration |
*ConfigurationManagementApi* | [**updateCodeTemplate**](Apis/ConfigurationManagementApi.md#updatecodetemplate) | **PUT** /configuration/code/{id} | Update code template |
*ConfigurationManagementApi* | [**updateConnectionConfiguration**](Apis/ConfigurationManagementApi.md#updateconnectionconfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update connector configuration |
*ConfigurationManagementApi* | [**updateServiceConfiguration**](Apis/ConfigurationManagementApi.md#updateserviceconfiguration) | **PUT** /configuration/service | Update service configuration |
| *DeploymentControllerApi* | [**getDeploymentMap**](Apis/DeploymentControllerApi.md#getdeploymentmap) | **GET** /deployment/defined | Get complete deployment configuration |
*DeploymentControllerApi* | [**getDeploymentMapEntry**](Apis/DeploymentControllerApi.md#getdeploymentmapentry) | **GET** /deployment/defined/{mappingIdentifier} | Get deployment configuration for mapping |
*DeploymentControllerApi* | [**getMappingsDeployed**](Apis/DeploymentControllerApi.md#getmappingsdeployed) | **GET** /deployment/effective | Get effective deployments |
*DeploymentControllerApi* | [**updateDeploymentMapEntry**](Apis/DeploymentControllerApi.md#updatedeploymentmapentry) | **PUT** /deployment/defined/{mappingIdentifier} | Update deployment configuration for mapping |
| *DeviceSubscriptionControllerApi* | [**createSubscription**](Apis/DeviceSubscriptionControllerApi.md#createsubscription) | **POST** /subscription | Create device notification subscription |
*DeviceSubscriptionControllerApi* | [**deleteGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#deletegroupsubscription) | **DELETE** /subscription/group/{groupId} | Delete device group notification subscription |
*DeviceSubscriptionControllerApi* | [**deleteSubscription**](Apis/DeviceSubscriptionControllerApi.md#deletesubscription) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
*DeviceSubscriptionControllerApi* | [**getGroupSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getgroupsubscriptions) | **GET** /subscription/group | Get group notification subscriptions |
*DeviceSubscriptionControllerApi* | [**getSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getsubscriptions) | **GET** /subscription | Get device notification subscriptions |
*DeviceSubscriptionControllerApi* | [**getTypeSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#gettypesubscriptions) | **GET** /subscription/type | Get device type notification subscriptions |
*DeviceSubscriptionControllerApi* | [**resyncTypeSubscription**](Apis/DeviceSubscriptionControllerApi.md#resynctypesubscription) | **POST** /subscription/type/resync/{type} | Resync existing devices into a type subscription |
*DeviceSubscriptionControllerApi* | [**updateGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#updategroupsubscription) | **PUT** /subscription/group | Update group notification subscription (desired state) |
*DeviceSubscriptionControllerApi* | [**updateSubscription**](Apis/DeviceSubscriptionControllerApi.md#updatesubscription) | **PUT** /subscription | Update device notification subscription |
*DeviceSubscriptionControllerApi* | [**updateTypeSubscription**](Apis/DeviceSubscriptionControllerApi.md#updatetypesubscription) | **PUT** /subscription/type | Update device type notification subscription |
| *ExtensionControllerApi* | [**deleteProcessorExtension**](Apis/ExtensionControllerApi.md#deleteprocessorextension) | **DELETE** /extension/{extensionName} | Delete a processor extension |
*ExtensionControllerApi* | [**getProcessorExtension**](Apis/ExtensionControllerApi.md#getprocessorextension) | **GET** /extension/{extensionName} | Get a specific processor extension |
*ExtensionControllerApi* | [**getProcessorExtensions**](Apis/ExtensionControllerApi.md#getprocessorextensions) | **GET** /extension | Get all processor extensions |
| *HTTPConnectorControllerApi* | [**processGenericMessage**](Apis/HTTPConnectorControllerApi.md#processgenericmessage) | **POST** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage1**](Apis/HTTPConnectorControllerApi.md#processgenericmessage1) | **PUT** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage2**](Apis/HTTPConnectorControllerApi.md#processgenericmessage2) | **POST** /httpConnector/** | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage3**](Apis/HTTPConnectorControllerApi.md#processgenericmessage3) | **PUT** /httpConnector/** | Process HTTP connector message |
| *MappingControllerApi* | [**createMapping**](Apis/MappingControllerApi.md#createmapping) | **POST** /mapping | Create a new mapping |
*MappingControllerApi* | [**deleteDraft**](Apis/MappingControllerApi.md#deletedraft) | **DELETE** /mapping/{id}/draft | Discard the draft of a mapping |
*MappingControllerApi* | [**deleteMapping**](Apis/MappingControllerApi.md#deletemapping) | **DELETE** /mapping/{id} | Delete a mapping |
*MappingControllerApi* | [**deleteVersion**](Apis/MappingControllerApi.md#deleteversion) | **DELETE** /mapping/{id}/version/{version} | Delete a version of a mapping |
*MappingControllerApi* | [**getDraft**](Apis/MappingControllerApi.md#getdraft) | **GET** /mapping/{id}/draft | Get the draft (working copy) of a mapping |
*MappingControllerApi* | [**getMapping**](Apis/MappingControllerApi.md#getmapping) | **GET** /mapping/{id} | Get a specific mapping |
*MappingControllerApi* | [**getMappings**](Apis/MappingControllerApi.md#getmappings) | **GET** /mapping | Get all mappings |
*MappingControllerApi* | [**getVersion**](Apis/MappingControllerApi.md#getversion) | **GET** /mapping/{id}/version/{version} | Get a specific version of a mapping |
*MappingControllerApi* | [**getVersionCounts**](Apis/MappingControllerApi.md#getversioncounts) | **GET** /mapping/version-counts | Get published version counts for all mappings |
*MappingControllerApi* | [**getVersions**](Apis/MappingControllerApi.md#getversions) | **GET** /mapping/{id}/version | List versions of a mapping |
*MappingControllerApi* | [**publishDraft**](Apis/MappingControllerApi.md#publishdraft) | **POST** /mapping/{id}/publish | Publish the draft as a new version |
*MappingControllerApi* | [**saveDraft**](Apis/MappingControllerApi.md#savedraft) | **PUT** /mapping/{id}/draft | Save edits into the draft of a mapping |
*MappingControllerApi* | [**suggestNextVersions**](Apis/MappingControllerApi.md#suggestnextversions) | **GET** /mapping/{id}/version/suggest | Suggest the next semver labels for a mapping |
*MappingControllerApi* | [**updateMapping**](Apis/MappingControllerApi.md#updatemapping) | **PUT** /mapping/{id} | Update an existing mapping |
*MappingControllerApi* | [**updateVersionNote**](Apis/MappingControllerApi.md#updateversionnote) | **PATCH** /mapping/{id}/version/{version} | Update a version's note |
| *MessageExplorerControllerApi* | [**clearMessages**](Apis/MessageExplorerControllerApi.md#clearmessages) | **DELETE** /explorer/session/{sessionId}/messages | Clear buffered messages |
*MessageExplorerControllerApi* | [**getMessages**](Apis/MessageExplorerControllerApi.md#getmessages) | **GET** /explorer/session/{sessionId}/messages | Poll buffered messages |
*MessageExplorerControllerApi* | [**startSession**](Apis/MessageExplorerControllerApi.md#startsession) | **POST** /explorer/session | Start an explorer session |
*MessageExplorerControllerApi* | [**stopSession**](Apis/MessageExplorerControllerApi.md#stopsession) | **DELETE** /explorer/session/{sessionId} | Stop an explorer session |
| *MonitoringControllerApi* | [**getActiveSubscriptions**](Apis/MonitoringControllerApi.md#getactivesubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} | Get active subscriptions for connector |
*MonitoringControllerApi* | [**getConnectorStatus**](Apis/MonitoringControllerApi.md#getconnectorstatus) | **GET** /monitoring/status/connector/{connectorIdentifier} | Get connector status |
*MonitoringControllerApi* | [**getConnectorsStatus**](Apis/MonitoringControllerApi.md#getconnectorsstatus) | **GET** /monitoring/status/connectors | Get all connectors status |
*MonitoringControllerApi* | [**getInboundMappingTree**](Apis/MonitoringControllerApi.md#getinboundmappingtree) | **GET** /monitoring/tree | Get inbound mapping tree |
*MonitoringControllerApi* | [**getMappingStatus**](Apis/MonitoringControllerApi.md#getmappingstatus) | **GET** /monitoring/status/mapping/statistic | Get mapping statistics |
| *OperationControllerApi* | [**runOperation**](Apis/OperationControllerApi.md#runoperation) | **POST** /operation | Execute a service operation |
| *TestControllerApi* | [**echoHealth**](Apis/TestControllerApi.md#echohealth) | **GET** /webhook | Webhook health check |
*TestControllerApi* | [**echoInput**](Apis/TestControllerApi.md#echoinput) | **POST** /webhook/echo/** | Echo webhook input |
*TestControllerApi* | [**testMapping**](Apis/TestControllerApi.md#testmapping) | **POST** /test/mapping | Test a mapping |


<a name="documentation-for-models"></a>
## Documentation for Models

 - [CodeTemplate](./Models/CodeTemplate.md)
 - [ConnectorConfiguration](./Models/ConnectorConfiguration.md)
 - [ConnectorProperty](./Models/ConnectorProperty.md)
 - [ConnectorPropertyCondition](./Models/ConnectorPropertyCondition.md)
 - [ConnectorSpecification](./Models/ConnectorSpecification.md)
 - [ConnectorStatusEvent](./Models/ConnectorStatusEvent.md)
 - [Device](./Models/Device.md)
 - [DynamicMapperRequest](./Models/DynamicMapperRequest.md)
 - [DynamicMapperRequest_error](./Models/DynamicMapperRequest_error.md)
 - [DynamicMapperRequest_error_cause](./Models/DynamicMapperRequest_error_cause.md)
 - [DynamicMapperRequest_error_cause_stackTrace_inner](./Models/DynamicMapperRequest_error_cause_stackTrace_inner.md)
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
 - [ServiceOperation](./Models/ServiceOperation.md)
 - [StartSessionRequest](./Models/StartSessionRequest.md)
 - [Substitution](./Models/Substitution.md)
 - [TestContext](./Models/TestContext.md)
 - [TestResult](./Models/TestResult.md)


<a name="documentation-for-authorization"></a>
## Documentation for Authorization

<a name="basicAuth"></a>
### basicAuth

- **Type**: HTTP basic authentication

