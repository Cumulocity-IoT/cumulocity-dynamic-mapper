# Documentation for Cumulocity Dynamic Mapper

<a name="documentation-for-api-endpoints"></a>
## Documentation for API Endpoints

All URIs are relative to *http://localhost:8080*

| Class | Method | HTTP request | Description |
|------------ | ------------- | ------------- | -------------|
| *ConfigurationControllerApi* | [**createCodeTemplate**](Apis/ConfigurationControllerApi.md#createcodetemplate) | **POST** /configuration/code | Create a new code template |
*ConfigurationControllerApi* | [**createConnectorConfiguration**](Apis/ConfigurationControllerApi.md#createconnectorconfiguration) | **POST** /configuration/connector/instance | Create a new connector configuration |
*ConfigurationControllerApi* | [**deleteCodeTemplate**](Apis/ConfigurationControllerApi.md#deletecodetemplate) | **DELETE** /configuration/code/{id} | Delete a code template |
*ConfigurationControllerApi* | [**deleteConnectionConfiguration**](Apis/ConfigurationControllerApi.md#deleteconnectionconfiguration) | **DELETE** /configuration/connector/instance/{identifier} | Delete a connector configuration |
*ConfigurationControllerApi* | [**getCodeTemplate**](Apis/ConfigurationControllerApi.md#getcodetemplate) | **GET** /configuration/code/{id} | Liefert ein Code-Template |
*ConfigurationControllerApi* | [**getCodeTemplates**](Apis/ConfigurationControllerApi.md#getcodetemplates) | **GET** /configuration/code | Get all code templates |
*ConfigurationControllerApi* | [**getConnectionConfiguration**](Apis/ConfigurationControllerApi.md#getconnectionconfiguration) | **GET** /configuration/connector/instance/{identifier} | Get a connector configuration |
*ConfigurationControllerApi* | [**getConnectionConfigurations**](Apis/ConfigurationControllerApi.md#getconnectionconfigurations) | **GET** /configuration/connector/instance | Get all connector configurations |
*ConfigurationControllerApi* | [**getConnectorSpecifications**](Apis/ConfigurationControllerApi.md#getconnectorspecifications) | **GET** /configuration/connector/specifications | Get connectors with their specifications |
*ConfigurationControllerApi* | [**getFeatures**](Apis/ConfigurationControllerApi.md#getfeatures) | **GET** /configuration/feature | Get the feature flags for the dynamic mapper service |
*ConfigurationControllerApi* | [**getServiceConfiguration**](Apis/ConfigurationControllerApi.md#getserviceconfiguration) | **GET** /configuration/service | Liefert die Service-Konfiguration |
*ConfigurationControllerApi* | [**updateCodeTemplate**](Apis/ConfigurationControllerApi.md#updatecodetemplate) | **PUT** /configuration/code/{id} | Update a code template |
*ConfigurationControllerApi* | [**updateConnectionConfiguration**](Apis/ConfigurationControllerApi.md#updateconnectionconfiguration) | **PUT** /configuration/connector/instance/{identifier} | Update a connector configuration |
*ConfigurationControllerApi* | [**updateServiceConfiguration**](Apis/ConfigurationControllerApi.md#updateserviceconfiguration) | **PUT** /configuration/service | Aktualisiert die Service-Konfiguration |
| *DeploymentControllerApi* | [**getDeploymentMap**](Apis/DeploymentControllerApi.md#getdeploymentmap) | **GET** /deployment/defined |  |
*DeploymentControllerApi* | [**getDeploymentMapEntry**](Apis/DeploymentControllerApi.md#getdeploymentmapentry) | **GET** /deployment/defined/{mappingIdentifier} |  |
*DeploymentControllerApi* | [**getMappingsDeployed**](Apis/DeploymentControllerApi.md#getmappingsdeployed) | **GET** /deployment/effective |  |
*DeploymentControllerApi* | [**updateDeploymentMapEntry**](Apis/DeploymentControllerApi.md#updatedeploymentmapentry) | **PUT** /deployment/defined/{mappingIdentifier} |  |
| *DeviceSubscriptionControllerApi* | [**subscriptionCreate**](Apis/DeviceSubscriptionControllerApi.md#subscriptioncreate) | **POST** /subscription |  |
*DeviceSubscriptionControllerApi* | [**subscriptionDelete**](Apis/DeviceSubscriptionControllerApi.md#subscriptiondelete) | **DELETE** /subscription/{deviceId} |  |
*DeviceSubscriptionControllerApi* | [**subscriptionUpdate**](Apis/DeviceSubscriptionControllerApi.md#subscriptionupdate) | **PUT** /subscription |  |
*DeviceSubscriptionControllerApi* | [**subscriptionsGet**](Apis/DeviceSubscriptionControllerApi.md#subscriptionsget) | **GET** /subscription |  |
| *ExtensionControllerApi* | [**deleteProcessorExtension**](Apis/ExtensionControllerApi.md#deleteprocessorextension) | **DELETE** /extension/{extensionName} |  |
*ExtensionControllerApi* | [**getProcessorExtension**](Apis/ExtensionControllerApi.md#getprocessorextension) | **GET** /extension/{extensionName} |  |
*ExtensionControllerApi* | [**getProcessorExtensions**](Apis/ExtensionControllerApi.md#getprocessorextensions) | **GET** /extension |  |
| *HttpConnectorControllerApi* | [**processGenericMessage**](Apis/HttpConnectorControllerApi.md#processgenericmessage) | **POST** /httpConnector |  |
*HttpConnectorControllerApi* | [**processGenericMessage1**](Apis/HttpConnectorControllerApi.md#processgenericmessage1) | **PUT** /httpConnector |  |
*HttpConnectorControllerApi* | [**processGenericMessage2**](Apis/HttpConnectorControllerApi.md#processgenericmessage2) | **POST** /httpConnector/** |  |
*HttpConnectorControllerApi* | [**processGenericMessage3**](Apis/HttpConnectorControllerApi.md#processgenericmessage3) | **PUT** /httpConnector/** |  |
| *MappingControllerApi* | [**createMapping1**](Apis/MappingControllerApi.md#createmapping1) | **POST** /mapping |  |
*MappingControllerApi* | [**deleteMapping**](Apis/MappingControllerApi.md#deletemapping) | **DELETE** /mapping/{id} |  |
*MappingControllerApi* | [**getMapping**](Apis/MappingControllerApi.md#getmapping) | **GET** /mapping/{id} |  |
*MappingControllerApi* | [**getMappings**](Apis/MappingControllerApi.md#getmappings) | **GET** /mapping |  |
*MappingControllerApi* | [**updateMapping**](Apis/MappingControllerApi.md#updatemapping) | **PUT** /mapping/{id} |  |
| *MonitoringControllerApi* | [**getActiveSubscriptions**](Apis/MonitoringControllerApi.md#getactivesubscriptions) | **GET** /monitoring/subscription/{connectorIdentifier} |  |
*MonitoringControllerApi* | [**getConnectorStatus**](Apis/MonitoringControllerApi.md#getconnectorstatus) | **GET** /monitoring/status/connector/{connectorIdentifier} |  |
*MonitoringControllerApi* | [**getConnectorsStatus**](Apis/MonitoringControllerApi.md#getconnectorsstatus) | **GET** /monitoring/status/connectors |  |
*MonitoringControllerApi* | [**getInboundMappingTree**](Apis/MonitoringControllerApi.md#getinboundmappingtree) | **GET** /monitoring/tree |  |
*MonitoringControllerApi* | [**getMappingStatus**](Apis/MonitoringControllerApi.md#getmappingstatus) | **GET** /monitoring/status/mapping/statistic |  |
| *OperationControllerApi* | [**runOperation**](Apis/OperationControllerApi.md#runoperation) | **POST** /operation |  |
| *TestControllerApi* | [**echoHealth**](Apis/TestControllerApi.md#echohealth) | **GET** /webhook |  |
*TestControllerApi* | [**echoInput**](Apis/TestControllerApi.md#echoinput) | **POST** /webhook/echo/** |  |
*TestControllerApi* | [**forwardPayload**](Apis/TestControllerApi.md#forwardpayload) | **POST** /test/{method} |  |
| *WatsonControllerApi* | [**createMapping**](Apis/WatsonControllerApi.md#createmapping) | **POST** /watson/mapping |  |


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
 - [Context](./Models/Context.md)
 - [DeploymentMapEntry](./Models/DeploymentMapEntry.md)
 - [Device](./Models/Device.md)
 - [Engine](./Models/Engine.md)
 - [Extension](./Models/Extension.md)
 - [ExtensionEntry](./Models/ExtensionEntry.md)
 - [Feature](./Models/Feature.md)
 - [Instrument](./Models/Instrument.md)
 - [Language](./Models/Language.md)
 - [Mapping](./Models/Mapping.md)
 - [MappingStatus](./Models/MappingStatus.md)
 - [MappingTreeNode](./Models/MappingTreeNode.md)
 - [ProcessingContextObject](./Models/ProcessingContextObject.md)
 - [ServiceConfiguration](./Models/ServiceConfiguration.md)
 - [ServiceOperation](./Models/ServiceOperation.md)
 - [Source](./Models/Source.md)
 - [SourceSection](./Models/SourceSection.md)
 - [SourceSection_code](./Models/SourceSection_code.md)
 - [Source_characters](./Models/Source_characters.md)
 - [SubstituteValue](./Models/SubstituteValue.md)
 - [Substitution](./Models/Substitution.md)
 - [Value](./Models/Value.md)


<a name="documentation-for-authorization"></a>
## Documentation for Authorization

All endpoints do not require authorization.
