/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.svenson.JSONParser;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.BinariesApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import c8y.IsDevice;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.configuration.TrustedCertificateRepresentation;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Extension;
import mqtt.mapping.model.ExtensionEntry;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.extension.ExtensionsComponent;
import mqtt.mapping.notification.C8YAPISubscriber;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.extension.ExtensibleProcessorInbound;
import mqtt.mapping.processor.extension.ProcessorExtensionInbound;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class C8YAgent implements ImportBeanDefinitionRegistrar {

    private static final String PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL = "mqtt.mapping.processor.extension.external";

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryFacade inventoryApi;

    @Autowired
    private BinariesApi binaryApi;

    @Autowired
    private IdentityFacade identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private DeviceControlApi deviceControlApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private MQTTClient mqttClient;

    @Autowired
    public void setMQTTClient(@Lazy MQTTClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    public void setConnectionConfigurationComponent(ConnectionConfigurationComponent connectionConfigurationComponent) {
        this.connectionConfigurationComponent = connectionConfigurationComponent;
    }

    private ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    public void setServiceConfigurationComponent(ServiceConfigurationComponent serviceConfigurationComponent) {
        this.serviceConfigurationComponent = serviceConfigurationComponent;
    }

    private MappingComponent mappingComponent;

    @Autowired
    public void setMappingComponent(MappingComponent mappingComponent) {
        this.mappingComponent = mappingComponent;
    }

    private ExtensionsComponent extensions;

    @Autowired
    public void setExtensions(ExtensionsComponent extensions) {
        this.extensions = extensions;
    }

    Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound;

    @Autowired
    public void setPayloadProcessorsInbound(Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound) {
        this.payloadProcessorsInbound = payloadProcessorsInbound;
    }

    public C8YAPISubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(@Lazy C8YAPISubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    private ExtensibleProcessorInbound extensibleProcessor;

    private MappingServiceRepresentation mappingServiceRepresentation;

    private ManagedObjectRepresentation mappingServiceMOR;

    private JSONParser jsonParser = JSONBase.getJSONParser();

    @Getter
    @Setter
    private String tenant = null;

    private MicroserviceCredentials credentials;

    @Getter
    @Setter
    private ServiceConfiguration serviceConfiguration;

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";

    @EventListener
    public void destroy(MicroserviceSubscriptionRemovedEvent event) {
        log.info("Microservice unsubscribed for tenant {}", event.getTenant());
        // this.createEvent("MQTT Mapper Microservice terminated",
        // "mqtt_microservice_stopevent", DateTime.now(), null);
        notificationSubscriber.disconnect(null);
        if (mqttClient != null)
            mqttClient.disconnect();
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        mappingComponent.setTenant(tenant);
        serviceConfigurationComponent.setTenant(tenant);
        connectionConfigurationComponent.setTenant(tenant);

        credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        // register agent
        mappingServiceMOR = subscriptionsService.callForTenant(tenant, () -> {
            ExternalIDRepresentation mappingServiceIdRepresentation = resolveExternalId2GlobalId(
                        new ID(null, MappingServiceRepresentation.AGENT_ID),
                        null);;
            ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

            if (mappingServiceIdRepresentation != null) {
                log.info("Agent with ID {} already exists {}", MappingServiceRepresentation.AGENT_ID,
                        mappingServiceIdRepresentation);
                amo = mappingServiceIdRepresentation.getManagedObject();
            } else {
                amo.setName(MappingServiceRepresentation.AGENT_NAME);
                amo.set(new Agent());
                amo.set(new IsDevice());
                amo.setProperty(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT,
                        new ArrayList<>());
                amo = inventoryApi.create(amo, null);
                log.info("Agent has been created with ID {}", amo.getId());
                ExternalIDRepresentation externalAgentId = identityApi.create(amo,
                        new ID("c8y_Serial",
                                MappingServiceRepresentation.AGENT_ID),
                        null);
                log.debug("ExternalId created: {}", externalAgentId.getExternalId());
            }
            extensibleProcessor = (ExtensibleProcessorInbound) payloadProcessorsInbound
                    .get(MappingType.PROCESSOR_EXTENSION);

            // test if managedObject for internal mapping extension exists
            MutableObject<ManagedObjectRepresentation> internalExtension = new MutableObject<ManagedObjectRepresentation>(
                    null);

            extensions.getInternal().forEach(m -> {
                internalExtension.setValue(m);
            });
            if (internalExtension.getValue() == null) {
                ManagedObjectRepresentation ie = new ManagedObjectRepresentation();
                Map<String, ?> props = Map.of("name",
                        ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                        "external", false);
                ie.setProperty(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE,
                        props);
                ie.setName(ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME);
                internalExtension.setValue(inventoryApi.create(ie, null));
            }
            log.info("Internal extension: {} registered: {}",
                    ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                    internalExtension.getValue().getId().getValue(), internalExtension.getValue());
            notificationSubscriber.init();
            // notificationSubscriber.subscribeTenant(tenant);
            // notificationSubscriber.subscribeAllDevices();
            return amo;

        });
        serviceConfiguration = serviceConfigurationComponent.loadServiceConfiguration();
        loadProcessorExtensions();
        mappingServiceRepresentation = objectMapper.convertValue(mappingServiceMOR, MappingServiceRepresentation.class);
        mappingComponent.initializeMappingComponent(mappingServiceRepresentation);

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation measurementRepresentation = subscriptionsService.callForTenant(tenant, () -> {
            MeasurementRepresentation mr = new MeasurementRepresentation();
            mr.setAttrs(attributes);
            mr.setSource(mor);
            mr.setType(eventType);
            mr.setDateTime(timestamp);

            // Step 3: Iterate over all fragments provided
            Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
            while (fragmentKeys.hasNext()) {
                Map.Entry<String, Object> currentFragment = fragmentKeys.next();
                mr.set(currentFragment.getValue(), currentFragment.getKey());
            }
            return measurementApi.create(mr);
        });
        return measurementRepresentation;
    }

    public ExternalIDRepresentation resolveExternalId2GlobalId(ID identity, ProcessingContext<?> context) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveExternalId2GlobalId(identity, context);
            } catch (SDKException e) {
                log.warn("External ID {} not found", identity.getValue());
            }
            return null;
        });
        return result;
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(GId gid, String idType, ProcessingContext<?> context) {
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveGlobalId2ExternalId(gid, idt, context);
            } catch (SDKException e) {
                log.warn("External ID type {} for {} not found", idt, gid.getValue());
            }
            return null;
        });
        return result;
    }

    public ManagedObjectRepresentation getAgentMOR() {
        return mappingServiceMOR;
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                measurementRepresentation.set(mvMap, name);
                measurementRepresentation.setType(type);
                measurementRepresentation.setSource(mor);
                measurementRepresentation.setDateTime(dateTime);
                log.debug("Creating Measurement {}", measurementRepresentation);
                MeasurementRepresentation mrn = measurementApi.create(measurementRepresentation);
                measurementRepresentation.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Error creating Measurement", e);
            }
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation alarmRepresentation = subscriptionsService.callForTenant(tenant, () -> {
            AlarmRepresentation ar = new AlarmRepresentation();
            ar.setSeverity(severity);
            ar.setSource(parentMor);
            ar.setText(message);
            ar.setDateTime(alarmTime);
            ar.setStatus("ACTIVE");
            ar.setType(type);
            return this.alarmApi.create(ar);
        });
        return alarmRepresentation;
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        subscriptionsService.runForTenant(tenant, () -> {
            EventRepresentation er = new EventRepresentation();
            er.setSource(parentMor != null ? parentMor : mappingServiceMOR);
            er.setText(message);
            er.setDateTime(eventTime);
            er.setType(type);
            this.eventApi.createAsync(er);
        });
    }

    public MQTTClient.Certificate loadCertificateByName(String fingerprint) {
        TrustedCertificateRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            return serviceConfigurationComponent.loadCertificateByName(fingerprint, credentials);
        });
        log.info("Found certificate with fingerprint: {}", result.getFingerprint());
        StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n")
                .append(result.getCertInPemFormat())
                .append("\n").append("-----END CERTIFICATE-----");

        return new MQTTClient.Certificate(result.getFingerprint(), cert.toString());
    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context) throws ProcessingException {
        StringBuffer error = new StringBuffer("");
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        API targetAPI = context.getMapping().getTargetAPI();
        AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            AbstractExtensibleRepresentation rt = null;
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation eventRepresentation = objectMapper.readValue(payload,
                            EventRepresentation.class);
                    rt = eventApi.create(eventRepresentation);
                    log.info("New event posted: {}", rt);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = objectMapper.readValue(payload,
                            AlarmRepresentation.class);
                    rt = alarmApi.create(alarmRepresentation);
                    log.info("New alarm posted: {}", rt);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    rt = measurementApi.create(measurementRepresentation);
                    log.info("New measurement posted: {}", rt);
                } else if (targetAPI.equals(API.OPERATION)) {
                    OperationRepresentation operationRepresentation = jsonParser
                            .parse(OperationRepresentation.class, payload);
                    rt = deviceControlApi.create(operationRepresentation);
                    log.info("New operation posted: {}", rt);
                } else {
                    log.error("Not existing API!");
                }
            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {} {}", targetAPI, payload);
                error.append("Could not map payload: " + targetAPI + "/" + payload);
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
                error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
            }
            return rt;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return result;
    }

    public ManagedObjectRepresentation upsertDevice(ID identity, ProcessingContext<?> context)
            throws ProcessingException {
        StringBuffer error = new StringBuffer("");
        C8YRequest currentRequest = context.getCurrentRequest();
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                    ManagedObjectRepresentation.class);
            try {
                ExternalIDRepresentation extId = resolveExternalId2GlobalId(identity, context);
                if (extId == null) {
                    // Device does not exist
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    mor = inventoryApi.create(mor, context);
                    log.info("New device created: {}", mor);
                    identityApi.create(mor, identity, context);
                } else {
                    // Device exists - update needed
                    mor.setId(extId.getManagedObject().getId());
                    mor = inventoryApi.update(mor, context);

                    log.info("Device updated: {}", mor);
                }
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {}", currentRequest.getRequest(), s);
                error.append("Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s);
            }
            return mor;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return device;
    }

    private void loadProcessorExtensions() {
        ClassLoader inernalClassloader = C8YAgent.class.getClassLoader();
        ClassLoader externalClassLoader = null;

        for (ManagedObjectRepresentation extension : extensions.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.info("Trying to load extension id: {}, name: {}, ", extension.getId().getValue(), extName);

            try {
                if (external) {
                    // step 1 download extension for binary repository
                    InputStream downloadInputStream = binaryApi.downloadFile(extension.getId());

                    // step 2 create temporary file,because classloader needs a url resource
                    File tempFile =  File.createTempFile(extName, "jar");
                    tempFile.deleteOnExit();
                    String canonicalPath = tempFile.getCanonicalPath();
                    String path = tempFile.getPath();
                    String pathWithProtocol = "file://".concat(tempFile.getPath());
                    log.info("CanonicalPath: {}, Path: {}, PathWithProtocol: {}", canonicalPath, path,
                            pathWithProtocol);
                    FileOutputStream outputStream = new FileOutputStream(tempFile);
                    IOUtils.copy(downloadInputStream, outputStream);

                    // step 3 parse list of extentions
                    URL[] urls = { tempFile.toURI().toURL() };
                    externalClassLoader = new URLClassLoader(urls, mqtt.mapping.App.class.getClassLoader());
                    registerExtensionInProcessor(extension.getId().getValue(), extName, externalClassLoader, external);
                } else {
                    registerExtensionInProcessor(extension.getId().getValue(), extName, inernalClassloader, external);
                }
            } catch (IOException e) {
                log.error("Exception occured, When loading extension, starting without extensions!", e);
                // e.printStackTrace();
            }
        }
    }

    private void registerExtensionInProcessor(String id, String extName, ClassLoader dynamicLoader, boolean external)
            throws IOException {
        extensibleProcessor.addExtension(id, extName, external);
        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
        InputStreamReader in = null;
        try {
            in = new InputStreamReader(resourceAsStream);
        } catch (Exception e) {
            log.error("Registration file: {} missing, ignoring to load extensions from: {}", resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }
        BufferedReader buffered = new BufferedReader(in);
        Properties newExtensions = new Properties();

        if (buffered != null)
            newExtensions.load(buffered);
        log.info("Preparing to load extensions:" + newExtensions.toString());

        Enumeration<?> extensions = newExtensions.propertyNames();
        while (extensions.hasMoreElements()) {
            String key = (String) extensions.nextElement();
            Class<?> clazz;
            ExtensionEntry extensionEntry = new ExtensionEntry(key, newExtensions.getProperty(key),
                    null, true, "OK");
            extensibleProcessor.addExtensionEntry(extName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));
                if (external && !clazz.getPackageName().startsWith(PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL)) {
                    extensionEntry.setMessage(
                            "Implementation must be in package: 'mqtt.mapping.processor.extension.external' instead of: "
                                    + clazz.getPackageName());
                    extensionEntry.setLoaded(false);
                } else {
                    Object object = clazz.getDeclaredConstructor().newInstance();
                    if (!(object instanceof ProcessorExtensionInbound)) {
                        String msg = String.format(
                                "Extension: %s=%s is not instance of ProcessorExtension, ignoring this entry!", key,
                                newExtensions.getProperty(key));
                        log.warn(msg);
                        extensionEntry.setLoaded(false);
                    } else {
                        ProcessorExtensionInbound<?> extensionImpl = (ProcessorExtensionInbound<?>) clazz
                                .getDeclaredConstructor()
                                .newInstance();
                        // springUtil.registerBean(key, clazz);
                        extensionEntry.setExtensionImplementation(extensionImpl);
                        log.info("Successfully registered bean: {} for key: {}", newExtensions.getProperty(key),
                                key);
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Could not load extension: %s:%s, ignoring loading!", key,
                        newExtensions.getProperty(key));
                log.warn(msg);
                e.printStackTrace();
                extensionEntry.setLoaded(false);
            }
        }
        extensibleProcessor.updateStatusExtension(extName);
    }

    public Map<String, Extension> getProcessorExtensions() {
        return extensibleProcessor.getExtensions();
    }

    public Extension getProcessorExtension(String extension) {
        return extensibleProcessor.getExtension(extension);
    }

    public Extension deleteProcessorExtension(String extensionName) {
        for (ManagedObjectRepresentation extensionRepresentation : extensions.get()) {
            if (extensionName.equals(extensionRepresentation.getName())) {
                binaryApi.deleteFile(extensionRepresentation.getId());
                log.info("Deleted extension: {} permanently!", extensionName);
            }
        }
        return extensibleProcessor.deleteExtension(extensionName);
    }

    public void reloadExtensions() {
        extensibleProcessor.deleteExtensions();
        loadProcessorExtensions();
    }

    public ManagedObjectRepresentation getManagedObjectForId(String deviceId) {
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return inventoryApi.get(GId.asGId(deviceId));
            } catch (SDKException exception) {
                log.warn("Device with id {} not found!", deviceId);
            }
            return null;
        });

        return device;
    }

    public void updateOperationStatus(OperationRepresentation op, OperationStatus status, String failureReason) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                op.setStatus(status.toString());
                if (failureReason != null)
                    op.setFailureReason(failureReason);
                deviceControlApi.update(op);
            } catch (SDKException exception) {
                log.error("Operation with id {} could not be updated: {}", op.getDeviceId().getValue(),
                        exception.getLocalizedMessage());
            }
        });
    }

    public void notificationSubscriberReconnect() {
        subscriptionsService.runForTenant(tenant, () -> {
            // notificationSubscriber.disconnect(false);
            // notificationSubscriber.reconnect();
            notificationSubscriber.disconnect(false);
            notificationSubscriber.init();
        });
    }
}
