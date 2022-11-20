package mqtt.mapping.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import javax.annotation.PreDestroy;

import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.svenson.JSONParser;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.rest.representation.tenant.auth.TrustedCertificateRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.BinariesApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import c8y.IsDevice;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.ClassLoaderUtil;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ConfigurationService;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.extension.ProcessorExtensionsRepresentation;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Extension;
import mqtt.mapping.model.ExtensionEntry;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.processor.BasePayloadProcessor;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.extension.ExtensibleProcessor;
import mqtt.mapping.processor.extension.ProcessorExtension;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;
import mqtt.mapping.service.ServiceStatus;

@Slf4j
@Service
public class C8YAgent implements ImportBeanDefinitionRegistrar {

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

    @Autowired
    private MQTTClient mqttClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ProcessorExtensionsRepresentation extensions;

    @Autowired
    Map<MappingType, BasePayloadProcessor<?>> payloadProcessors;
    // @Autowired
    private ExtensibleProcessor<?> extensibleProcessor;

    private MappingServiceRepresentation mappingServiceRepresentation;

    private JSONParser jsonParser = JSONBase.getJSONParser();

    public String tenant = null;

    private MicroserviceCredentials credentials;

    private static final Method ADD_URL_METHOD;

    static {
        final Method addURL;

        // open the classloader module for java9+ so it wont have a warning
        try {
            openUrlClassLoaderModule();
        } catch (Throwable ignored) {
            // ignore exception. Java 8 wont have the module, so it wont matter if we ignore
            // it
            // cause there will be no warning
        }

        try {
            addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }

        ADD_URL_METHOD = addURL;
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRepresentation = null;
            ManagedObjectRepresentation agentRepresentation = null;
            try {
                agentIdRepresentation = resolveExternalId(new ID(null, MappingServiceRepresentation.AGENT_ID), null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRepresentation != null) {
                log.info("Agent with ID {} already exists {}", MappingServiceRepresentation.AGENT_ID,
                        agentIdRepresentation);
                agentRepresentation = agentIdRepresentation.getManagedObject();
            } else {
                agentRepresentation = new ManagedObjectRepresentation();
                agentRepresentation.setName(MappingServiceRepresentation.AGENT_NAME);
                agentRepresentation.set(new Agent());
                agentRepresentation.set(new IsDevice());
                agentRepresentation.setProperty(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT,
                        new ArrayList<>());
                agentRepresentation = inventoryApi.create(agentRepresentation, null);
                log.info("Agent has been created with ID {}", agentRepresentation.getId());
                ExternalIDRepresentation externalAgentId = identityApi.create(agentRepresentation, new ID("c8y_Serial",
                        MappingServiceRepresentation.AGENT_ID), null);
                log.debug("ExternalId created: {}", externalAgentId.getExternalId());
            }
            agentRepresentation = inventoryApi.get(agentRepresentation.getId());
            mappingServiceRepresentation = objectMapper.convertValue(agentRepresentation,
                    MappingServiceRepresentation.class);
            mappingServiceRepresentation.getMappingStatus().forEach(m -> {
                mqttClient.initializeMappingStatus(m);
            });

            // test if managedObject mqttMapping exists
            ExternalIDRepresentation mappingsRepresentationMappingExtId = resolveExternalId(new ID("c8y_Serial",
                    MappingsRepresentation.MQTT_MAPPING_TYPE), null);
            if (mappingsRepresentationMappingExtId == null) {
                // create new managedObject
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setType(MappingsRepresentation.MQTT_MAPPING_TYPE);
                // moMapping.set([], MQTT_MAPPING_FRAGMENT);
                mor.setProperty(MappingsRepresentation.MQTT_MAPPING_FRAGMENT, new ArrayList<>());
                mor.setName("MQTT-Mapping");
                mor = inventoryApi.create(mor, null);
                identityApi.create(mor, new ID("c8y_Serial", MappingsRepresentation.MQTT_MAPPING_TYPE), null);
                log.info("Created new MQTT-Mapping: {}, {}", mor.getId().getValue(), mor.getId());
            }

            extensibleProcessor = (ExtensibleProcessor<?>) payloadProcessors.get(MappingType.PROCESSOR_EXTENSION);
            loadProcessorExtensions();
        });

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        measurementRepresentation.setAttrs(attributes);
        measurementRepresentation.setSource(mor);
        measurementRepresentation.setType(eventType);
        measurementRepresentation.setDateTime(timestamp);

        // Step 3: Iterate over all fragments provided
        Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
        while (fragmentKeys.hasNext()) {
            Map.Entry<String, Object> currentFragment = fragmentKeys.next();
            measurementRepresentation.set(currentFragment.getValue(), currentFragment.getKey());

        }
        measurementRepresentation = measurementApi.create(measurementRepresentation);
        return measurementRepresentation;
    }

    public ExternalIDRepresentation resolveExternalId(ID identity, ProcessingContext<?> context) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation[] externalIDRepresentations = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                externalIDRepresentations[0] = identityApi.getExternalId(identity, context);
            } catch (SDKException e) {
                log.warn("External ID {} not found", identity.getValue());
            }
        });
        return externalIDRepresentations[0];
    }

    public void unregisterDevice(ID identity) {
        ExternalIDRepresentation externalIDRepresentation = resolveExternalId(identity, null);
        if (externalIDRepresentation != null) {
            inventoryApi.delete(externalIDRepresentation.getManagedObject().getId());
        }
    }

    public void storeEvent(EventRepresentation event) {
        eventApi.createAsync(event).get();
    }

    public MappingServiceRepresentation getAgentMOR() {
        return mappingServiceRepresentation;
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
        AlarmRepresentation[] alarmRepresentations = { new AlarmRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            alarmRepresentations[0].setSeverity(severity);
            alarmRepresentations[0].setSource(parentMor);
            alarmRepresentations[0].setText(message);
            alarmRepresentations[0].setDateTime(alarmTime);
            alarmRepresentations[0].setStatus("ACTIVE");
            alarmRepresentations[0].setType(type);

            alarmRepresentations[0] = this.alarmApi.create(alarmRepresentations[0]);
        });
        return alarmRepresentations[0];
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] eventRepresentations = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            eventRepresentations[0].setSource(parentMor != null ? parentMor : mappingServiceRepresentation);
            eventRepresentations[0].setText(message);
            eventRepresentations[0].setDateTime(eventTime);
            eventRepresentations[0].setType(type);
            this.eventApi.createAsync(eventRepresentations[0]);
        });
    }

    public ArrayList<Mapping> getMappings() {
        ArrayList<Mapping> result = new ArrayList<Mapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MappingsRepresentation mqttMo = objectMapper.convertValue(mo, MappingsRepresentation.class);
            log.debug("Found mappings: {}", mqttMo);
            result.addAll(mqttMo.getC8yMQTTMapping());
            log.info("Found mappings: {}", result.size());
        });
        return result;
    }

    public ConfigurationConnection loadConnectionConfiguration() {
        ConfigurationConnection[] results = { new ConfigurationConnection() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConnectionConfiguration();
            // COMMENT OUT ONLY DEBUG
            // results[0].active = true;
            log.info("Found connection configuration: {}", results[0]);
        });
        return results[0];
    }

    public MQTTClient.Certificate loadCertificateByName(String fingerprint) {
        TrustedCertificateRepresentation[] results = { new TrustedCertificateRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadCertificateByName(fingerprint, credentials);
            log.info("Found certificate with fingerprint: {}", results[0].getFingerprint());
        });
        StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n").append(results[0].getCertInPemFormat())
                .append("\n").append("-----END CERTIFICATE-----");

        return new MQTTClient.Certificate(results[0].getFingerprint(), cert.toString());
    }

    public void saveConnectionConfiguration(ConfigurationConnection configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConnectionConfiguration(configuration);
                log.debug("Saved connection configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public ServiceConfiguration loadServiceConfiguration() {
        ServiceConfiguration[] results = { new ServiceConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadServiceConfiguration();
            log.info("Found service configuration: {}", results[0]);
        });
        return results[0];
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveServiceConfiguration(configuration);
                log.debug("Saved service configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context) throws ProcessingException {
        String[] errors = { "" };
        AbstractExtensibleRepresentation[] results = { null };
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        API targetAPI = context.getMapping().getTargetAPI();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation eventRepresentation = objectMapper.readValue(payload,
                            EventRepresentation.class);
                    results[0] = eventApi.create(eventRepresentation);
                    log.info("New event posted: {}", results[0]);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = objectMapper.readValue(payload,
                            AlarmRepresentation.class);
                    results[0] = alarmApi.create(alarmRepresentation);
                    log.info("New alarm posted: {}", results[0]);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    // MeasurementRepresentation mr = objectMapper.readValue(payload,
                    // MeasurementRepresentation.class);
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    results[0] = measurementApi.create(measurementRepresentation);
                    log.info("New measurement posted: {}", results[0]);
                } else if (targetAPI.equals(API.OPERATION)) {
                    OperationRepresentation operationRepresentation = jsonParser
                            .parse(OperationRepresentation.class, payload);
                    results[0] = deviceControlApi.create(operationRepresentation);
                    log.info("New operation posted: {}", results[0]);
                } else {
                    log.error("Not existing API!");
                }
            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {} {}", targetAPI, payload);
                errors[0] = "Could not map payload: " + targetAPI + "/" + payload;
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
                errors[0] = "Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
        return results[0];
    }

    public ManagedObjectRepresentation upsertDevice(ID identity, ProcessingContext<?> context)
            throws ProcessingException {
        String[] errors = { "" };
        C8YRequest currentRequest = context.getCurrentRequest();
        ManagedObjectRepresentation[] devices = { new ManagedObjectRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                ExternalIDRepresentation extId = resolveExternalId(identity, context);
                if (extId == null) {
                    // Device does not exist
                    ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                            ManagedObjectRepresentation.class);
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    mor = inventoryApi.create(mor, context);
                    log.info("New device created: {}", mor);
                    devices[0] = mor;
                    identityApi.create(mor, identity, context);
                } else {
                    // Device exists - update needed
                    ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                            ManagedObjectRepresentation.class);
                    mor.setId(extId.getManagedObject().getId());
                    mor = inventoryApi.update(mor, context);
                    devices[0] = mor;
                    log.info("Device updated: {}", mor);
                }

            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {}", currentRequest.getRequest());
                errors[0] = "Could not map payload: " + currentRequest.getRequest();
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {}", currentRequest.getRequest(), s);
                errors[0] = "Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
        return devices[0];
    }

    public void saveMappings(List<Mapping> mappings) throws JsonProcessingException {
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mor = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            ManagedObjectRepresentation updateMOR = new ManagedObjectRepresentation();
            updateMOR.setId(mor.getId());
            updateMOR.setProperty(MappingsRepresentation.MQTT_MAPPING_FRAGMENT, mappings);
            inventoryApi.update(updateMOR, null);
            log.debug("Updated Mapping after deletion!");
        });
    }

    public ConfigurationConnection enableConnection(boolean b) {
        ConfigurationConnection[] configurations = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            configurations[0] = configurationService.enableConnection(b);
            log.debug("Saved configuration");
        });
        return configurations[0];
    }

    public Mapping getMapping(Long id) {
        Mapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MappingsRepresentation mappingsRepresentation = objectMapper.convertValue(mo, MappingsRepresentation.class);
            log.debug("Found Mapping {}", mappingsRepresentation);
            mappingsRepresentation.getC8yMQTTMapping().forEach((m) -> {
                if (m.id == id) {
                    mr[0] = m;
                }
            });
            log.info("Found Mapping {}", mr[0]);
        });
        return mr[0];
    }

    public void sendStatusMapping(String type, Map<String, MappingStatus> mappingStatus) {
        // avoid sending empty monitoring events
        if (mappingStatus.values().size() > 0) {
            log.debug("Sending monitoring: {}", mappingStatus.values().size());
            subscriptionsService.runForTenant(tenant, () -> {
                Map<String, Object> service = new HashMap<String, Object>();
                MappingStatus[] array = mappingStatus.values().toArray(new MappingStatus[0]);
                service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, array);
                ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                updateMor.setId(mappingServiceRepresentation.getId());
                updateMor.setAttrs(service);
                this.inventoryApi.update(updateMor, null);
            });
        } else {
            log.debug("Ignoring monitoring: {}", mappingStatus.values().size());
        }
    }

    public void sendStatusService(String type, ServiceStatus serviceStatus) {
        log.debug("Sending status configuration: {}", serviceStatus);
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put(MappingServiceRepresentation.SERVICE_STATUS_FRAGMENT, entry);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(mappingServiceRepresentation.getId());
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor, null);
        });
    }

    private void loadProcessorExtensions() {
        if (mqttClient.getServiceConfiguration().isExternalExtensionEnabled()){
            loadExternalProcessorExtensions();
        } {
            loadInternalProcessorExtensions();
        }

    }

    private void loadInternalProcessorExtensions() {
        ClassLoader dynamicLoader = C8YAgent.class.getClassLoader();
        registerExtensionInProcessor("0815", "mqtt-mapping-internal", dynamicLoader);
    }

    private void loadExternalProcessorExtensions() {
        // ExtensibleProcessor extensibleProcessor = (ExtensibleProcessor)
        // payloadProcessors.get(MappingType.PROCESSOR_EXTENSION);
        for (ManagedObjectRepresentation bObj : extensions.get()) {
            String extName = bObj.getProperty(ProcessorExtensionsRepresentation.PROCESSOR_EXTENSION_TYPE).toString();
            log.info("Copying extension binary , Id: " + bObj.getId().getValue() + ", name: " + extName);
            log.debug("Copying extension binary , Id: " + bObj);
            
            // step 1 download extension for binary repository
            
            InputStream downloadInputStream = binaryApi.downloadFile(bObj.getId());
            try {
                
                // step 2 create temporary file,because classloader needs a url resource
                
                File tempFile;
                tempFile = File.createTempFile(extName, "jar");
                tempFile.deleteOnExit();
                String canonicalPath = tempFile.getCanonicalPath();
                String path = tempFile.getPath();
                String pathWithProtocol = "file://".concat(tempFile.getPath());
                log.info("CanonicalPath: {}, Path: {}, PathWithProtocol: {}", canonicalPath, path, pathWithProtocol);
                FileOutputStream outputStream = new FileOutputStream(tempFile);
                IOUtils.copy(downloadInputStream, outputStream);
                
                // step 3 parse list of extentions
                
                ClassLoader dynamicLoader = ClassLoaderUtil.getClassLoader(pathWithProtocol, extName);
                
                registerExtensionInProcessor(bObj.getId().getValue(), extName, dynamicLoader);

            } catch (IOException e) {
                log.error("Exception occured, When loading extension, starting without extensions!", e);
                e.printStackTrace();
            }
        }
    }

    private void registerExtensionInProcessor(String id, String extName, ClassLoader dynamicLoader) {
        extensibleProcessor.addExtension(id, extName);
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream("extension.properties");
        BufferedReader buffered = new BufferedReader(new InputStreamReader(resourceAsStream));
        
        Properties newExtensions = new Properties();
        try {
            if (buffered != null)
                newExtensions.load(buffered);
            log.info("Preparing to load extensions:" + newExtensions.toString());
        } catch (IOException io) {
            io.printStackTrace();
        }

        Enumeration<?> extensions = newExtensions.propertyNames();
        while (extensions.hasMoreElements()) {
            String key = (String) extensions.nextElement();
            Class<?> clazz;
            ExtensionEntry extensionEntry = new ExtensionEntry(key, newExtensions.getProperty(key),
                    null, true);
            extensibleProcessor.addExtensionEntry(extName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));
                Object object = clazz.getDeclaredConstructor().newInstance();
                if (!(object instanceof ProcessorExtension)) {
                    log.warn("Extension: {}={} is not instance of ProcessorExtension, ignoring this enty!", key,
                            newExtensions.getProperty(key));
                } else {
                    ProcessorExtension<?> extensionImpl = (ProcessorExtension<?>) clazz.getDeclaredConstructor()
                            .newInstance();
                    // springUtil.registerBean(key, clazz);
                    extensionEntry.setExtensionImplementation(extensionImpl);
                    log.info("Sucessfully registered bean: {} for key: {}", newExtensions.getProperty(key),
                            key);
                }
            } catch (Exception e) {
                log.warn("Could not load extension: {}:{}, ignoring loading!", key,
                        newExtensions.getProperty(key));
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

    public String deleteProcessorExtension(String extensionName) {
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

    private static void openUrlClassLoaderModule() throws Exception {
        Class<?> moduleClass = Class.forName("java.lang.Module");
        Method addOpensMethod = moduleClass.getMethod("addOpens", String.class, moduleClass);

        Method getModuleMethod = Class.class.getMethod("getModule");
        Object urlClassLoaderModule = getModuleMethod.invoke(URLClassLoader.class);

        Object thisModule = getModuleMethod.invoke(C8YAgent.class);
        Module thisTypedModule = (Module) thisModule;
        log.info("This module: {}, {}", thisModule.getClass(), thisTypedModule.getName());

        addOpensMethod.invoke(urlClassLoaderModule, URLClassLoader.class.getPackage().getName(), thisModule);
    }
}
