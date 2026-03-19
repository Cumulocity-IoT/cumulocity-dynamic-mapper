# Documentation for Cumulocity Dynamic Mapper

<a name="documentation-for-api-endpoints"></a>
## Documentation for API Endpoints

All URIs are relative to *http://localhost:8080*

| Class | Method | HTTP request | Description |
|------------ | ------------- | ------------- | -------------|
| *CacheControllerApi* | [**getCacheSize**](Apis/CacheControllerApi.md#getcachesize) | **GET** /cache | Get cache size |
| *ClientRelationControllerApi* | [**addOrUpdateClientRelations**](Apis/ClientRelationControllerApi.md#addorupdateclientrelations) | **PUT** /relation/client/{clientId} | Add or update client relations |
*ClientRelationControllerApi* | [**clearAllClientRelations**](Apis/ClientRelationControllerApi.md#clearallclientrelations) | **DELETE** /relation/client | Clear all client relations |
*ClientRelationControllerApi* | [**getAllClientRelations**](Apis/ClientRelationControllerApi.md#getallclientrelations) | **GET** /relation/client | Get all client relations |
*ClientRelationControllerApi* | [**getAllClients**](Apis/ClientRelationControllerApi.md#getallclients) | **GET** /relation/clients | Get all clients |
*ClientRelationControllerApi* | [**getClientForDevice**](Apis/ClientRelationControllerApi.md#getclientfordevice) | **GET** /relation/device/{deviceId}/client | Get client for device |
*ClientRelationControllerApi* | [**getDevicesForClient**](Apis/ClientRelationControllerApi.md#getdevicesforclient) | **GET** /relation/client/{clientId}/devices | Get devices for client |
*ClientRelationControllerApi* | [**removeAllRelationsForClient**](Apis/ClientRelationControllerApi.md#removeallrelationsforclient) | **DELETE** /relation/client/{clientId}/delete | Remove all relations for client |
*ClientRelationControllerApi* | [**removeRelationForDevice**](Apis/ClientRelationControllerApi.md#removerelationfordevice) | **DELETE** /relation/device/{deviceId} | Remove relation for device |
| *ConfigurationControllerApi* | [**createCodeTemplate**](Apis/ConfigurationControllerApi.md#createcodetemplate) | **POST** /configuration/code | Create a new code template |
*ConfigurationControllerApi* | [**createConnectorConfiguration**](Apis/ConfigurationControllerApi.md#createconnectorconfiguration) | **POST** /configuration/connector/instance | Create a new connector configuration |
*ConfigurationControllerApi* | [**deleteCodeTemplate**](Apis/ConfigurationControllerApi.md#deletecodetemplate) | **DELETE** /configuration/code/{id} | Delete a code template |
*ConfigurationControllerApi* | [**deleteConnectionConfiguration**](Apis/ConfigurationControllerApi.md#deleteconnectionconfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete a connector configuration |
*ConfigurationControllerApi* | [**getCodeTemplate**](Apis/ConfigurationControllerApi.md#getcodetemplate) | **GET** /configuration/code/{id} | Get a code template |
*ConfigurationControllerApi* | [**getCodeTemplates**](Apis/ConfigurationControllerApi.md#getcodetemplates) | **GET** /configuration/code | Get all code templates |
*ConfigurationControllerApi* | [**getConnectionConfiguration**](Apis/ConfigurationControllerApi.md#getconnectionconfiguration) | **GET** /configuration/connector/instance/{identifier} | Get a connector configuration |
*ConfigurationControllerApi* | [**getConnectionConfigurations**](Apis/ConfigurationControllerApi.md#getconnectionconfigurations) | **GET** /configuration/connector/instance | Get all connector configurations |
*ConfigurationControllerApi* | [**getConnectorSpecifications**](Apis/ConfigurationControllerApi.md#getconnectorspecifications) | **GET** /configuration/connector/specifications | Get connectors with their specifications |
*ConfigurationControllerApi* | [**getFeatures**](Apis/ConfigurationControllerApi.md#getfeatures) | **GET** /configuration/feature | Get the feature flags for the dynamic mapper service |
*ConfigurationControllerApi* | [**getServiceConfiguration**](Apis/ConfigurationControllerApi.md#getserviceconfiguration) | **GET** /configuration/service | Get Service Configuration |
*ConfigurationControllerApi* | [**updateCodeTemplate**](Apis/ConfigurationControllerApi.md#updatecodetemplate) | **PUT** /configuration/code/{id} | Update a code template |
*ConfigurationControllerApi* | [**updateConnectionConfiguration**](Apis/ConfigurationControllerApi.md#updateconnectionconfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update a connector configuration |
*ConfigurationControllerApi* | [**updateServiceConfiguration**](Apis/ConfigurationControllerApi.md#updateserviceconfiguration) | **PUT** /configuration/service | Update the service configuration |
| *DeploymentControllerApi* | [**getDeploymentMap**](Apis/DeploymentControllerApi.md#getdeploymentmap) | **GET** /deployment/defined | Get complete deployment configuration |
*DeploymentControllerApi* | [**getDeploymentMapEntry**](Apis/DeploymentControllerApi.md#getdeploymentmapentry) | **GET** /deployment/defined/{mappingIdentifier} | Get deployment configuration for mapping |
*DeploymentControllerApi* | [**getMappingsDeployed**](Apis/DeploymentControllerApi.md#getmappingsdeployed) | **GET** /deployment/effective | Get effective deployments |
*DeploymentControllerApi* | [**updateDeploymentMapEntry**](Apis/DeploymentControllerApi.md#updatedeploymentmapentry) | **PUT** /deployment/defined/{mappingIdentifier} | Update deployment configuration for mapping |
| *DeviceSubscriptionControllerApi* | [**deleteGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#deletegroupsubscription) | **DELETE** /subscription/group/{groupId} | Delete device group notification subscription |
*DeviceSubscriptionControllerApi* | [**getGroupSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#getgroupsubscriptions) | **GET** /subscription/group | Get group notification subscriptions |
*DeviceSubscriptionControllerApi* | [**getTypeSubscriptions**](Apis/DeviceSubscriptionControllerApi.md#gettypesubscriptions) | **GET** /subscription/type | Get device type notification subscriptions |
*DeviceSubscriptionControllerApi* | [**subscriptionCreate**](Apis/DeviceSubscriptionControllerApi.md#subscriptioncreate) | **POST** /subscription | Create device notification subscription |
*DeviceSubscriptionControllerApi* | [**subscriptionDelete**](Apis/DeviceSubscriptionControllerApi.md#subscriptiondelete) | **DELETE** /subscription/{deviceId} | Delete device notification subscription |
*DeviceSubscriptionControllerApi* | [**subscriptionUpdate**](Apis/DeviceSubscriptionControllerApi.md#subscriptionupdate) | **PUT** /subscription | Update device notification subscription |
*DeviceSubscriptionControllerApi* | [**subscriptionsGet**](Apis/DeviceSubscriptionControllerApi.md#subscriptionsget) | **GET** /subscription | Get device notification subscriptions |
*DeviceSubscriptionControllerApi* | [**updateGroupSubscription**](Apis/DeviceSubscriptionControllerApi.md#updategroupsubscription) | **PUT** /subscription/group | Update group notification subscription |
*DeviceSubscriptionControllerApi* | [**updateTypeSubscription**](Apis/DeviceSubscriptionControllerApi.md#updatetypesubscription) | **PUT** /subscription/type | Update device type notification subscription |
| *ExtensionControllerApi* | [**deleteProcessorExtension**](Apis/ExtensionControllerApi.md#deleteprocessorextension) | **DELETE** /extension/{extensionName} | Delete a processor extension |
*ExtensionControllerApi* | [**getProcessorExtension**](Apis/ExtensionControllerApi.md#getprocessorextension) | **GET** /extension/{extensionName} | Get a specific processor extension |
*ExtensionControllerApi* | [**getProcessorExtensions**](Apis/ExtensionControllerApi.md#getprocessorextensions) | **GET** /extension | Get all processor extensions |
| *HTTPConnectorControllerApi* | [**processGenericMessage**](Apis/HTTPConnectorControllerApi.md#processgenericmessage) | **POST** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage1**](Apis/HTTPConnectorControllerApi.md#processgenericmessage1) | **PUT** /httpConnector | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage2**](Apis/HTTPConnectorControllerApi.md#processgenericmessage2) | **POST** /httpConnector/** | Process HTTP connector message |
*HTTPConnectorControllerApi* | [**processGenericMessage3**](Apis/HTTPConnectorControllerApi.md#processgenericmessage3) | **PUT** /httpConnector/** | Process HTTP connector message |
| *MappingControllerApi* | [**createMapping1**](Apis/MappingControllerApi.md#createmapping1) | **POST** /mapping | Create a new mapping |
*MappingControllerApi* | [**deleteMapping**](Apis/MappingControllerApi.md#deletemapping) | **DELETE** /mapping/{id} | Delete a mapping |
*MappingControllerApi* | [**getMapping**](Apis/MappingControllerApi.md#getmapping) | **GET** /mapping/{id} | Get a specific mapping |
*MappingControllerApi* | [**getMappings**](Apis/MappingControllerApi.md#getmappings) | **GET** /mapping | Get all mappings |
*MappingControllerApi* | [**updateMapping**](Apis/MappingControllerApi.md#updatemapping) | **PUT** /mapping/{id} | Update an existing mapping |
| *MonitoringControllerApi* | [**getActiveSubscriptions**](Apis/MonitoringControllerApi.md#getactivesubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} | Get active subscriptions for connector |
*MonitoringControllerApi* | [**getConnectorStatus**](Apis/MonitoringControllerApi.md#getconnectorstatus) | **GET** /monitoring/status/connector/{connectorIdentifier} | Get connector status |
*MonitoringControllerApi* | [**getConnectorsStatus**](Apis/MonitoringControllerApi.md#getconnectorsstatus) | **GET** /monitoring/status/connectors | Get all connectors status |
*MonitoringControllerApi* | [**getInboundMappingTree**](Apis/MonitoringControllerApi.md#getinboundmappingtree) | **GET** /monitoring/tree | Get inbound mapping tree |
*MonitoringControllerApi* | [**getMappingStatus**](Apis/MonitoringControllerApi.md#getmappingstatus) | **GET** /monitoring/status/mapping/statistic | Get mapping statistics |
| *OperationControllerApi* | [**runOperation**](Apis/OperationControllerApi.md#runoperation) | **POST** /operation | Execute a service operation |
| *TestControllerApi* | [**echoHealth**](Apis/TestControllerApi.md#echohealth) | **GET** /webhook |  |
*TestControllerApi* | [**echoInput**](Apis/TestControllerApi.md#echoinput) | **POST** /webhook/echo/** |  |
*TestControllerApi* | [**testMapping**](Apis/TestControllerApi.md#testmapping) | **POST** /test/mapping | Test a mapping |


<a name="documentation-for-models"></a>
## Documentation for Models

 - [BinaryInfo](./Models/BinaryInfo.md)
 - [C8YNotificationSubscription](./Models/C8YNotificationSubscription.md)
 - [C8YRequest](./Models/C8YRequest.md)
 - [C8YRequest_error](./Models/C8YRequest_error.md)
 - [C8YRequest_error_cause](./Models/C8YRequest_error_cause.md)
 - [C8YRequest_error_cause_stackTrace_inner](./Models/C8YRequest_error_cause_stackTrace_inner.md)
 - [CodeTemplate](./Models/CodeTemplate.md)
 - [ConnectorConfiguration](./Models/ConnectorConfiguration.md)
 - [ConnectorProperty](./Models/ConnectorProperty.md)
 - [ConnectorPropertyCondition](./Models/ConnectorPropertyCondition.md)
 - [ConnectorSpecification](./Models/ConnectorSpecification.md)
 - [ConnectorStatusEvent](./Models/ConnectorStatusEvent.md)
 - [Device](./Models/Device.md)
 - [Extension](./Models/Extension.md)
 - [ExtensionEntry](./Models/ExtensionEntry.md)
 - [Feature](./Models/Feature.md)
 - [Mapping](./Models/Mapping.md)
 - [MappingStatus](./Models/MappingStatus.md)
 - [MappingTreeNode](./Models/MappingTreeNode.md)
 - [NotificationSubscriptionRequest](./Models/NotificationSubscriptionRequest.md)
 - [NotificationSubscriptionResponse](./Models/NotificationSubscriptionResponse.md)
 - [ServiceConfiguration](./Models/ServiceConfiguration.md)
 - [ServiceOperation](./Models/ServiceOperation.md)
 - [SubstituteValue](./Models/SubstituteValue.md)
 - [Substitution](./Models/Substitution.md)
 - [TestContext](./Models/TestContext.md)
 - [TestResult](./Models/TestResult.md)


<a name="documentation-for-authorization"></a>
## Documentation for Authorization

All endpoints do not require authorization.
