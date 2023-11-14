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

package dynamic.mapping.core;

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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.core.MediaType;

import dynamic.mapping.App;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.TrustedCertificateCollectionRepresentation;
import dynamic.mapping.configuration.TrustedCertificateRepresentation;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.ExtensionEntry;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.PayloadProcessor;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.extension.ExtensionsComponent;
import dynamic.mapping.processor.extension.ProcessorExtensionInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.svenson.JSONParser;

import com.cumulocity.microservice.context.credentials.Credentials;
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
import com.cumulocity.sdk.client.Platform;
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
import dynamic.mapping.model.API;
import dynamic.mapping.notification.C8YAPISubscriber;

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
    private Platform platform;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;


    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    private ExtensionsComponent extensions;

    @Autowired
    public void setExtensions(ExtensionsComponent extensions) {
        this.extensions = extensions;
    }

    @Getter
    private C8YAPISubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(@Lazy C8YAPISubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    private Map<String, ExtensibleProcessorInbound> extensibleProcessors = new HashMap<>();

    private JSONParser jsonParser = JSONBase.getJSONParser();

    @Getter
    @Setter
    private ServiceConfiguration serviceConfiguration;

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";

    public ExternalIDRepresentation resolveExternalId2GlobalId(String tenant, ID identity, ProcessingContext<?> context) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveExternalId2GlobalId(identity, context);
            } catch (SDKException e) {
                log.warn("Tenant {} - External ID {} not found", tenant, identity.getValue());
            }
            return null;
        });
        return result;
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(String tenant, GId gid, String idType, ProcessingContext<?> context) {
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveGlobalId2ExternalId(gid, idt, context);
            } catch (SDKException e) {
                log.warn("Tenant {} - External ID type {} for {} not found", tenant, idt, gid.getValue());
            }
            return null;
        });
        return result;
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap, String tenant) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                measurementRepresentation.set(mvMap, name);
                measurementRepresentation.setType(type);
                measurementRepresentation.setSource(mor);
                measurementRepresentation.setDateTime(dateTime);
                log.debug("Tenant {} - Creating Measurement {}", tenant, measurementRepresentation);
                MeasurementRepresentation mrn = measurementApi.create(measurementRepresentation);
                measurementRepresentation.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Tenant {} - Error creating Measurement", tenant, e);
            }
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor, String tenant) {
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

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor, String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            EventRepresentation er = new EventRepresentation();
            er.setSource(parentMor);
            er.setText(message);
            er.setDateTime(eventTime);
            er.setType(type);
            this.eventApi.createAsync(er);
        });
    }

    public AConnectorClient.Certificate loadCertificateByName(String certificateName, Credentials credentials) {
        TrustedCertificateRepresentation result = subscriptionsService.callForTenant(subscriptionsService.getTenant(), () -> {
            MutableObject<TrustedCertificateRepresentation> certResult = new MutableObject<TrustedCertificateRepresentation>(
                    new TrustedCertificateRepresentation());
            TrustedCertificateCollectionRepresentation certificates = platform.rest().get(
                    String.format("/tenant/tenants/%s/trusted-certificates", credentials.getTenant()),
                    MediaType.APPLICATION_JSON_TYPE, TrustedCertificateCollectionRepresentation.class);
            certificates.forEach(cert -> {
                if (cert.getName().equals(certificateName)) {
                    certResult.setValue(cert);
                    log.debug("Found certificate with fingerprint: {} with name: {}",cert.getFingerprint(),
                            cert.getName());
                }
            });
            return certResult.getValue();
        });
        log.info("Found certificate with fingerprint: {}", result.getFingerprint());
        StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n")
                .append(result.getCertInPemFormat())
                .append("\n").append("-----END CERTIFICATE-----");

        return new AConnectorClient.Certificate(result.getFingerprint(), cert.toString());
    }

    public AbstractExtensibleRepresentation createMEAO(String tenant, ProcessingContext<?> context) throws ProcessingException {
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
                    log.info("Tenant {} - New event posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = objectMapper.readValue(payload,
                            AlarmRepresentation.class);
                    rt = alarmApi.create(alarmRepresentation);
                    log.info("Tenant {} - New alarm posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    rt = measurementApi.create(measurementRepresentation);
                    log.info("Tenant {} - New measurement posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.OPERATION)) {
                    OperationRepresentation operationRepresentation = jsonParser
                            .parse(OperationRepresentation.class, payload);
                    rt = deviceControlApi.create(operationRepresentation);
                    log.info("Tenant {} - New operation posted: {}", tenant, rt);
                } else {
                    log.error("Tenant {} - Not existing API!", tenant);
                }
            } catch (JsonProcessingException e) {
                log.error("Tenant {} - Could not map payload: {} {}", tenant, targetAPI, payload);
                error.append("Could not map payload: " + targetAPI + "/" + payload);
            } catch (SDKException s) {
                log.error("Tenant {} - Could not sent payload to c8y: {} {} {}", tenant, targetAPI, payload, s);
                error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
            }
            return rt;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return result;
    }

    public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context)
            throws ProcessingException {
        StringBuffer error = new StringBuffer("");
        C8YRequest currentRequest = context.getCurrentRequest();
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                    ManagedObjectRepresentation.class);
            try {
                ExternalIDRepresentation extId = resolveExternalId2GlobalId( tenant, identity, context);
                if (extId == null) {
                    // Device does not exist
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    // remove id
                    mor.setId(null);

                    mor = inventoryApi.create(mor, context);
                    log.info("Tenant {} - New device created: {}", tenant, mor);
                    identityApi.create(mor, identity, context);
                } else {
                    // Device exists - update needed
                    mor.setId(extId.getManagedObject().getId());
                    mor = inventoryApi.update(mor, context);

                    log.info("Tenant {} - Device updated: {}", tenant, mor);
                }
            } catch (SDKException s) {
                log.error("Tenant {} - Could not sent payload to c8y: {} {}", tenant, currentRequest.getRequest(), s);
                error.append("Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s);
            }
            return mor;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return device;
    }

    public void loadProcessorExtensions(String tenant) {
        ClassLoader inernalClassloader = C8YAgent.class.getClassLoader();
        ClassLoader externalClassLoader = null;

        for (ManagedObjectRepresentation extension : extensions.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.info("Tenant {} - Trying to load extension id: {}, name: {}", tenant, extension.getId().getValue(), extName);

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
                    log.debug("CanonicalPath: {}, Path: {}, PathWithProtocol: {}", canonicalPath, path,
                            pathWithProtocol);
                    FileOutputStream outputStream = new FileOutputStream(tempFile);
                    IOUtils.copy(downloadInputStream, outputStream);

                    // step 3 parse list of extentions
                    URL[] urls = { tempFile.toURI().toURL() };
                    externalClassLoader = new URLClassLoader(urls, App.class.getClassLoader());
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, externalClassLoader, external);
                } else {
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, inernalClassloader, external);
                }
            } catch (IOException e) {
                log.error("Tenant {} - Exception occured, When loading extension, starting without extensions!", tenant, e);
                // e.printStackTrace();
            }
        }
    }

    private void registerExtensionInProcessor(String tenant, String id, String extName, ClassLoader dynamicLoader, boolean external)
            throws IOException {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        extensibleProcessor.addExtension(id, extName, external);
        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
        InputStreamReader in = null;
        try {
            in = new InputStreamReader(resourceAsStream);
        } catch (Exception e) {
            log.error("Tenant {} - Registration file: {} missing, ignoring to load extensions from: {}", tenant, resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }
        BufferedReader buffered = new BufferedReader(in);
        Properties newExtensions = new Properties();

        if (buffered != null)
            newExtensions.load(buffered);
        log.info("Tenant {} - Preparing to load extensions:" + newExtensions.toString(), tenant);

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
                        log.info("Tenant {} - Successfully registered bean: {} for key: {}", tenant, newExtensions.getProperty(key),
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

    public Map<String, Extension> getProcessorExtensions(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        return extensibleProcessor.getExtensions();
    }

    public Extension getProcessorExtension(String tenant, String extension) {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        return extensibleProcessor.getExtension(extension);
    }

    public Extension deleteProcessorExtension(String tenant, String extensionName) {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        for (ManagedObjectRepresentation extensionRepresentation : extensions.get()) {
            if (extensionName.equals(extensionRepresentation.getName())) {
                binaryApi.deleteFile(extensionRepresentation.getId());
                log.info("Tenant {} - Deleted extension: {} permanently!", tenant, extensionName);
            }
        }
        return extensibleProcessor.deleteExtension(extensionName);
    }

    public void reloadExtensions(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = extensibleProcessors.get(tenant);
        extensibleProcessor.deleteExtensions();
        loadProcessorExtensions(tenant);
    }

    public ManagedObjectRepresentation getManagedObjectForId(String tenant, String deviceId) {
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return inventoryApi.get(GId.asGId(deviceId));
            } catch (SDKException exception) {
                log.warn("Tenant {} - Device with id {} not found!", tenant, deviceId);
            }
            return null;
        });

        return device;
    }

    public void updateOperationStatus(String tenant, OperationRepresentation op, OperationStatus status, String failureReason) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                op.setStatus(status.toString());
                if (failureReason != null)
                    op.setFailureReason(failureReason);
                deviceControlApi.update(op);
            } catch (SDKException exception) {
                log.error("Tenant {} - Operation with id {} could not be updated: {}", tenant, op.getDeviceId().getValue(),
                        exception.getLocalizedMessage());
            }
        });
    }


    public void notificationSubscriberReconnect(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            // notificationSubscriber.disconnect(false);
            // notificationSubscriber.reconnect();
            notificationSubscriber.disconnect(tenant, false);
            notificationSubscriber.init();
        });
    }


    public ManagedObjectRepresentation createMappingObject(String tenant) {
        ExternalIDRepresentation mappingServiceIdRepresentation = resolveExternalId2GlobalId(tenant,
                new ID(null, MappingServiceRepresentation.AGENT_ID),
                null);;
        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

        if (mappingServiceIdRepresentation != null) {
            amo =  inventoryApi.get(mappingServiceIdRepresentation.getManagedObject().getId());
            log.info("Tenant {} - Agent with ID {} already exists {} , {}", tenant, MappingServiceRepresentation.AGENT_ID,
                    mappingServiceIdRepresentation, amo);
        } else {
            amo.setName(MappingServiceRepresentation.AGENT_NAME);
            amo.set(new Agent());
            amo.set(new IsDevice());
            amo.setProperty(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT,
                    new ArrayList<>());
            amo = inventoryApi.create(amo, null);
            log.info("Tenant {} - Agent has been created with ID {}", tenant, amo.getId());
            ExternalIDRepresentation externalAgentId = identityApi.create(amo,
                    new ID("c8y_Serial",
                            MappingServiceRepresentation.AGENT_ID),
                    null);
            log.debug("Tenant {} - ExternalId created: {}", tenant, externalAgentId.getExternalId());
        }
        return amo;
    }

    public void checkExtensions(String tenant, PayloadProcessor payloadProcessor) {

        ExtensibleProcessorInbound extensibleProcessor = (ExtensibleProcessorInbound) payloadProcessor.getPayloadProcessorsInbound()
                .get(MappingType.PROCESSOR_EXTENSION);
        extensibleProcessors.put(tenant, extensibleProcessor);

        // test if managedObject for internal mapping extension exists
        List<ManagedObjectRepresentation> internalExtension = extensions.getInternal();
        ManagedObjectRepresentation ie = new ManagedObjectRepresentation();
        if (internalExtension == null || internalExtension.size() == 0 ) {
            Map<String, ?> props = Map.of("name",
                    ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                    "external", false);
            ie.setProperty(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE,
                    props);
            ie.setName(ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME);
            ie = inventoryApi.create(ie, null);
        } else {
            ie = internalExtension.get(0);
        }
        log.info("Tenant {} - Internal extension: {} registered: {}", tenant,
                ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                ie.getId().getValue(), ie);
    }
}
