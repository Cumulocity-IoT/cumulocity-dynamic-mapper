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

import c8y.IsDevice;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
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
import dynamic.mapping.App;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.TrustedCertificateCollectionRepresentation;
import dynamic.mapping.configuration.TrustedCertificateRepresentation;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.cache.InboundExternalIdCache;
import dynamic.mapping.core.facade.IdentityFacade;
import dynamic.mapping.core.facade.InventoryFacade;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.ExtensionEntry;
import dynamic.mapping.model.ExtensionType;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.extension.ExtensibleProcessor;
import dynamic.mapping.processor.extension.ExtensionsComponent;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.extension.ProcessorExtensionTarget;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.svenson.JSONParser;

import javax.ws.rs.core.MediaType;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.util.Map.entry;

@Slf4j
@Component
public class C8YAgent implements ImportBeanDefinitionRegistrar {

	ConnectorStatus previousConnectorStatus = ConnectorStatus.UNKNOWN;

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

	@Autowired
	private ContextService<MicroserviceCredentials> contextService;

	private ExtensionsComponent extensionsComponent;

	@Autowired
	public void setExtensionsComponent(ExtensionsComponent extensionsComponent) {
		this.extensionsComponent = extensionsComponent;
	}

	@Getter
	private Map<String, InboundExternalIdCache> inboundExternalIdCaches = new HashMap<>();

	@Getter
	private ConfigurationRegistry configurationRegistry;

	@Autowired
	public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
		this.configurationRegistry = configurationRegistry;
	}

	private JSONParser jsonParser = JSONBase.getJSONParser();

	public static final String MAPPING_FRAGMENT = "d11r_mapping";

	public static final String CONNECTOR_FRAGMENT = "d11r_connector";
	public static final String DEPLOYMENT_MAP_FRAGMENT = "d11r_deploymentMap";

	public static final String STATUS_SUBSCRIPTION_EVENT_TYPE = "d11r_subscriptionEvent";
	public static final String STATUS_CONNECTOR_EVENT_TYPE = "d11r_connectorStatusEvent";
	public static final String STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE = "d11r_mappingActivationErrorEvent";
	public static final String STATUS_MAPPING_CHANGED_EVENT_TYPE = "d11r_mappingChangedEvent";
	public static final String STATUS_NOTIFICATION_EVENT_TYPE = "d11r_notificationStatusEvent";

	private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
	private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";

	private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

	private static final String PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL = "dynamic.mapping.processor.extension.external";

	@Value("${application.version}")
	private String version;

	public ExternalIDRepresentation resolveExternalId2GlobalId(String tenant, ID identity,
			ProcessingContext<?> context) {
		if (identity.getType() == null) {
			identity.setType("c8y_Serial");
		}
		ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
			try {
				ExternalIDRepresentation resultInner = this.getInboundExternalIdCache(tenant)
						.getIdByExternalId(identity);
				Counter.builder("dynmapper_inbound_identity_requests_total").tag("tenant", tenant)
						.register(Metrics.globalRegistry).increment();
				if (resultInner == null) {
					resultInner = identityApi.resolveExternalId2GlobalId(identity, context);
					this.getInboundExternalIdCache(tenant).putIdForExternalId(identity,
							resultInner);

				} else {
					log.debug("Tenant {} - Cache hit for external ID {} -> {}", tenant, identity.getValue(),
							resultInner.getManagedObject().getId().getValue());
					Counter.builder("dynmapper_inbound_identity_cache_hits_total").tag("tenant", tenant)
							.register(Metrics.globalRegistry).increment();
				}
				return resultInner;
			} catch (SDKException e) {
				log.warn("Tenant {} - External ID {} not found", tenant, identity.getValue());
			}
			return null;
		});
		return result;
	}

	public ExternalIDRepresentation resolveGlobalId2ExternalId(String tenant, GId gid, String idType,
			ProcessingContext<?> context) {
		// TODO Use Cache
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
			MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
			contextService.runWithinContext(context, () -> {
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
		});
		return measurementRepresentation;
	}

	public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
			ManagedObjectRepresentation parentMor, String tenant) {
		AlarmRepresentation alarmRepresentation = subscriptionsService.callForTenant(tenant, () -> {
			MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
			return contextService.callWithinContext(context, () -> {
				AlarmRepresentation ar = new AlarmRepresentation();
				ar.setSeverity(severity);
				ar.setSource(parentMor);
				ar.setText(message);
				ar.setDateTime(alarmTime);
				ar.setStatus("ACTIVE");
				ar.setType(type);
				return this.alarmApi.create(ar);
			});
		});
		return alarmRepresentation;
	}

	public void createEvent(String message, String type, DateTime eventTime, MappingServiceRepresentation source,
			String tenant, Map<String, String> properties) {
		subscriptionsService.runForTenant(tenant, () -> {
			MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
			contextService.runWithinContext(context, () -> {
				EventRepresentation er = new EventRepresentation();
				ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
				mor.setId(new GId(source.getId()));
				er.setSource(mor);
				er.setText(message);
				er.setDateTime(eventTime);
				er.setType(type);
				if (properties != null) {
					er.setProperty(C8YAgent.CONNECTOR_FRAGMENT, properties);
				}
				this.eventApi.createAsync(er);
			});
		});
	}

	public AConnectorClient.Certificate loadCertificateByName(String certificateName, String fingerprint,
			String tenant, String connectorName) {
		TrustedCertificateRepresentation result = subscriptionsService.callForTenant(tenant,
				() -> {
					log.info("Tenant {} - Connector {} - Retrieving certificate {} ", tenant, connectorName,
							certificateName);
					TrustedCertificateRepresentation certResult = null;
					try {
						List<TrustedCertificateRepresentation> certificatesList = new ArrayList<>();
						boolean next = true;
						String nextUrl = String.format("/tenant/tenants/%s/trusted-certificates", tenant);
						TrustedCertificateCollectionRepresentation certificatesResult;
						while (next) {
							certificatesResult = platform.rest().get(
									nextUrl,
									MediaType.APPLICATION_JSON_TYPE, TrustedCertificateCollectionRepresentation.class);
							certificatesList.addAll(certificatesResult.getCertificates());
							nextUrl = certificatesResult.getNext();
							next = certificatesResult.getCertificates().size() > 0;
							log.info("Tenant {} - Connector {} - Retrieved certificates {} - next {} - nextUrl {}",
									tenant,
									connectorName, certificatesList.size(), next, nextUrl);
						}
						for (int index = 0; index < certificatesList.size(); index++) {
							TrustedCertificateRepresentation certificateIterate = certificatesList.get(index);
							log.info("Tenant {} - Found certificate with fingerprint: {} with name: {}", tenant,
									certificateIterate.getFingerprint(),
									certificateIterate.getName());
							if (certificateIterate.getName().equals(certificateName)
									&& certificateIterate.getFingerprint().equals(fingerprint)) {
								certResult = certificateIterate;
								log.info("Tenant {} - Connector {} - Found certificate {} with fingerprint {} ", tenant,
										connectorName, certificateName, certificateIterate.getFingerprint());
								break;
							}
						}
					} catch (Exception e) {
						log.error("Tenant {} - Connector {} - Exception when initializing connector: ", tenant,
								connectorName, e);
					}
					return certResult;
				});
		if (result != null) {
			log.info("Tenant {} - Connector {} - Found certificate {} with fingerprint {} ", tenant,
					connectorName, certificateName, result.getFingerprint());
			StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n")
					.append(result.getCertInPemFormat())
					.append("\n").append("-----END CERTIFICATE-----");
			return new AConnectorClient.Certificate(result.getFingerprint(), cert.toString());
		} else {
			log.info("Tenant {} - Connector {} - No certificate found!", tenant, connectorName);
			return null;
		}
	}

	// TODO Change this to use ExecutorService + Virtual Threads when available
	public CompletableFuture<AbstractExtensibleRepresentation> createMEAOAsync(ProcessingContext<?> context)
			throws ProcessingException {
		return CompletableFuture.supplyAsync(() -> {
			String tenant = context.getTenant();
			StringBuffer error = new StringBuffer("");
			C8YRequest currentRequest = context.getCurrentRequest();
			String payload = currentRequest.getRequest();
			API targetAPI = context.getMapping().getTargetAPI();
			AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
				MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
				return contextService.callWithinContext(contextCredentials, () -> {
					AbstractExtensibleRepresentation rt = null;
					try {
						if (targetAPI.equals(API.EVENT)) {
							EventRepresentation eventRepresentation = configurationRegistry.getObjectMapper().readValue(
									payload,
									EventRepresentation.class);
							rt = eventApi.create(eventRepresentation);
							log.info("Tenant {} - New event posted: {}", tenant, rt);
						} else if (targetAPI.equals(API.ALARM)) {
							AlarmRepresentation alarmRepresentation = configurationRegistry.getObjectMapper().readValue(
									payload,
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
						log.error("Tenant {} - Could not sent payload to c8y: {} {}: ", tenant, targetAPI, payload, s);
						error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
					}
					return rt;
				});
			});
			if (!error.toString().equals("")) {
				throw new CompletionException(new ProcessingException(error.toString()));
			}
			return result;
		});
	}

	public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context)
			throws ProcessingException {
		String tenant = context.getTenant();
		StringBuffer error = new StringBuffer("");
		C8YRequest currentRequest = context.getCurrentRequest();
		String payload = currentRequest.getRequest();
		ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
		API targetAPI = context.getMapping().getTargetAPI();
		AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
			MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
			return contextService.callWithinContext(contextCredentials, () -> {
				AbstractExtensibleRepresentation rt = null;
				try {
					if (targetAPI.equals(API.EVENT)) {
						EventRepresentation eventRepresentation = configurationRegistry.getObjectMapper().readValue(
								payload,
								EventRepresentation.class);
						rt = eventApi.create(eventRepresentation);
						if (serviceConfiguration.logPayload)
							log.info("Tenant {} - New event posted: {}", tenant, rt);
						else
							log.info("Tenant {} - New event posted with Id {}", tenant,
									((EventRepresentation) rt).getId().getValue());
					} else if (targetAPI.equals(API.ALARM)) {
						AlarmRepresentation alarmRepresentation = configurationRegistry.getObjectMapper().readValue(
								payload,
								AlarmRepresentation.class);
						rt = alarmApi.create(alarmRepresentation);
						if (serviceConfiguration.logPayload)
							log.info("Tenant {} - New alarm posted: {}", tenant, rt);
						else
							log.info("Tenant {} - New alarm posted with Id {}", tenant,
									((AlarmRepresentation) rt).getId().getValue());
					} else if (targetAPI.equals(API.MEASUREMENT)) {
						MeasurementRepresentation measurementRepresentation = jsonParser
								.parse(MeasurementRepresentation.class, payload);
						rt = measurementApi.create(measurementRepresentation);
						if (serviceConfiguration.logPayload)
							log.info("Tenant {} - New measurement posted: {}", tenant, rt);
						else
							log.info("Tenant {} - New measurement posted with Id {}", tenant,
									((MeasurementRepresentation) rt).getId().getValue());
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
					log.error("Tenant {} - Could not sent payload to c8y: {} {}: ", tenant, targetAPI, payload, s);
					error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
				}
				return rt;
			});
		});
		if (!error.toString().equals("")) {
			throw new ProcessingException(error.toString());
		}
		return result;
	}

	public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context,
			ExternalIDRepresentation extId)
			throws ProcessingException {
		StringBuffer error = new StringBuffer("");
		C8YRequest currentRequest = context.getCurrentRequest();
		ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
		ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
			MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
			return contextService.callWithinContext(contextCredentials, () -> {
				ManagedObjectRepresentation mor = configurationRegistry.getObjectMapper().readValue(
						currentRequest.getRequest(),
						ManagedObjectRepresentation.class);
				try {
					// ExternalIDRepresentation extId = resolveExternalId2GlobalId(tenant, identity,
					// context);
					if (extId == null) {
						// Device does not exist
						// append external id to name
						mor.setName(mor.getName());
						/*
						 * mor.set(new Agent());
						 * HashMap<String, String> agentFragments = new HashMap<>();
						 * agentFragments.put("name", "Dynamic Data Mapper");
						 * agentFragments.put("version", version);
						 * agentFragments.put("url",
						 * "https://github.com/SoftwareAG/cumulocity-dynamic-mapper");
						 * agentFragments.put("maintainer", "Open-Source");
						 * mor.set(agentFragments, "c8y_Agent");
						 */
						mor.set(new IsDevice());
						// remove id
						mor.setId(null);

						mor = inventoryApi.create(mor, context);
						// TODO Add/Update new managed object to IdentityCache
						if (serviceConfiguration.logPayload)
							log.info("Tenant {} - New device created: {}", tenant, mor);
						else
							log.info("Tenant {} - New device created with Id {}", tenant, mor.getId().getValue());
						identityApi.create(mor, identity, context);
					} else {
						// Device exists - update needed
						mor.setId(extId.getManagedObject().getId());
						mor = inventoryApi.update(mor, context);
						if (serviceConfiguration.logPayload)
							log.info("Tenant {} - Device updated: {}", tenant, mor);
						else
							log.info("Tenant {} - Device {} updated.", tenant, mor.getId().getValue());
					}
				} catch (SDKException s) {
					log.error("Tenant {} - Could not sent payload to c8y: {}: ", tenant, currentRequest.getRequest(),
							s);
					error.append("Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s);
				}
				return mor;
			});
		});
		if (!error.toString().equals("")) {
			throw new ProcessingException(error.toString());
		}
		return device;
	}

	public void loadProcessorExtensions(String tenant) {
		ClassLoader internalClassloader = C8YAgent.class.getClassLoader();
		ClassLoader externalClassLoader = null;

		for (ManagedObjectRepresentation extension : extensionsComponent.get()) {
			Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
			String extName = props.get("name").toString();
			boolean external = (Boolean) props.get("external");
			log.debug("Tenant {} - Trying to load extension id: {}, name: {}", tenant, extension.getId().getValue(),
					extName);
			InputStream downloadInputStream = null;
			FileOutputStream outputStream = null;
			try {
				if (external) {
					// step 1 download extension for binary repository
					downloadInputStream = binaryApi.downloadFile(extension.getId());

					// step 2 create temporary file,because classloader needs a url resource
					File tempFile = File.createTempFile(extName, "jar");
					tempFile.deleteOnExit();
					String canonicalPath = tempFile.getCanonicalPath();
					String path = tempFile.getPath();
					String pathWithProtocol = "file://".concat(tempFile.getPath());
					log.debug("Tenant {} - CanonicalPath: {}, Path: {}, PathWithProtocol: {}", tenant, canonicalPath,
							path,
							pathWithProtocol);
					outputStream = new FileOutputStream(tempFile);
					IOUtils.copy(downloadInputStream, outputStream);

					// step 3 parse list of extensions
					URL[] urls = { tempFile.toURI().toURL() };
					externalClassLoader = new URLClassLoader(urls, App.class.getClassLoader());
					registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, externalClassLoader,
							external);
				} else {
					registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, internalClassloader,
							external);
				}
			} catch (IOException e) {
				log.error("Tenant {} - IO Exception occurred when loading extension: ", tenant, e);
			} catch (SecurityException e) {
				log.error("Tenant {} - Security Exception occurred when loading extension: ", tenant, e);
			} catch (IllegalArgumentException e) {
				log.error("Tenant {} - Invalid argument Exception occurred when loading extension: ", tenant, e);
			} finally {
				// Consider cleaning up resources here
				if (downloadInputStream != null) {
					try {
						downloadInputStream.close();
					} catch (IOException e) {
						log.warn("Tenant {} - Failed to close download stream", tenant, e);
					}
				}
				if (outputStream != null) {
					try {
						outputStream.close();
					} catch (IOException e) {
						log.warn("Tenant {} - Failed to close output stream", tenant, e);
					}
				}
			}
		}
	}

	private void registerExtensionInProcessor(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
			boolean external)
			throws IOException {
		ExtensibleProcessor extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
		extensibleProcessor.addExtension(tenant, new Extension(id, extensionName, external));
		String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
		InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
		InputStreamReader in = null;
		try {
			in = new InputStreamReader(resourceAsStream);
		} catch (Exception e) {
			log.error("Tenant {} - Registration file: {} missing, ignoring to load extensions from: {}", tenant,
					resource,
					(external ? "EXTERNAL" : "INTERNAL"));
			throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
					+ (external ? "EXTERNAL" : "INTERNAL"));
		}
		BufferedReader buffered = new BufferedReader(in);
		Properties newExtensions = new Properties();

		if (buffered != null)
			newExtensions.load(buffered);
		log.debug("Tenant {} - Preparing to load extensions:" + newExtensions.toString(), tenant);

		Enumeration<?> extensions = newExtensions.propertyNames();
		while (extensions.hasMoreElements()) {
			String key = (String) extensions.nextElement();
			Class<?> clazz;
			ExtensionEntry extensionEntry = ExtensionEntry.builder().eventName(key)
					.fqnClassName(newExtensions.getProperty(key)).loaded(true).message("OK").build();
			extensibleProcessor.addExtensionEntry(tenant, extensionName, extensionEntry);

			try {
				clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));

				if (external && !clazz.getPackageName().startsWith(PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL)) {
					extensionEntry.setMessage(
							"Implementation must be in package: 'dynamic.mapping.processor.extension.external' instead of: "
									+ clazz.getPackageName());
					extensionEntry.setLoaded(false);
				} else {
					Object object = clazz.getDeclaredConstructor().newInstance();
					if (object instanceof ProcessorExtensionSource) {
						ProcessorExtensionSource<?> extensionImpl = (ProcessorExtensionSource<?>) clazz
								.getDeclaredConstructor()
								.newInstance();
						// springUtil.registerBean(key, clazz);
						extensionEntry.setExtensionImplSource(extensionImpl);
						extensionEntry.setExtensionType(ExtensionType.EXTENSION_SOURCE);
						log.debug("Tenant {} - Successfully registered extensionImplSource : {} for key: {}",
								tenant,
								newExtensions.getProperty(key),
								key);
					}
					if (object instanceof ProcessorExtensionTarget) {
						ProcessorExtensionTarget<?> extensionImpl = (ProcessorExtensionTarget<?>) clazz
								.getDeclaredConstructor()
								.newInstance();
						// springUtil.registerBean(key, clazz);
						extensionEntry.setExtensionImplTarget(extensionImpl);
						// overwrite type since it implements both
						extensionEntry.setExtensionType(ExtensionType.EXTENSION_PASSTHROUGH);
						log.debug("Tenant {} - Successfully registered extensionImplTarget : {} for key: {}",
								tenant,
								newExtensions.getProperty(key),
								key);
					}
					if (!(object instanceof ProcessorExtensionSource)
							&& !(object instanceof ProcessorExtensionTarget)) {
						String msg = String.format(
								"Extension: %s=%s is not instance of ProcessorExtension, ignoring this entry!", key,
								newExtensions.getProperty(key));
						log.warn(msg);
						extensionEntry.setLoaded(false);
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
		extensibleProcessor.updateStatusExtension(extensionName);
	}

	public Map<String, Extension> getProcessorExtensions(String tenant) {
		ExtensibleProcessor extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
		return extensibleProcessor.getExtensions();
	}

	public Extension getProcessorExtension(String tenant, String extension) {
		ExtensibleProcessor extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
		return extensibleProcessor.getExtension(extension);
	}

	public Extension deleteProcessorExtension(String tenant, String extensionName) {
		ExtensibleProcessor extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
		for (ManagedObjectRepresentation extensionRepresentation : extensionsComponent.get()) {
			if (extensionName.equals(extensionRepresentation.getName())) {
				binaryApi.deleteFile(extensionRepresentation.getId());
				log.info("Tenant {} - Deleted extension: {} permanently!", tenant, extensionName);
			}
		}
		return extensibleProcessor.deleteExtension(extensionName);
	}

	public void reloadExtensions(String tenant) {
		ExtensibleProcessor extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
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

	public void updateOperationStatus(String tenant, OperationRepresentation op, OperationStatus status,
			String failureReason) {
		subscriptionsService.runForTenant(tenant, () -> {
			MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
			contextService.runWithinContext(contextCredentials, () -> {
				try {
					op.setStatus(status.toString());
					if (failureReason != null)
						op.setFailureReason(failureReason);
					deviceControlApi.update(op);
				} catch (SDKException exception) {
					log.error("Tenant {} - Operation with id {} could not be updated: {}", tenant,
							op.getDeviceId().getValue(),
							exception.getLocalizedMessage());
				}
			});
		});
	}

	public ManagedObjectRepresentation initializeMappingServiceObject(String tenant) {
		ExternalIDRepresentation mappingServiceIdRepresentation = resolveExternalId2GlobalId(tenant,
				new ID(null, MappingServiceRepresentation.AGENT_ID),
				null);
		;
		ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

		if (mappingServiceIdRepresentation != null) {
			amo = inventoryApi.get(mappingServiceIdRepresentation.getManagedObject().getId());
			log.info("Tenant {} - Agent with ID {} already exists {}", tenant,
					MappingServiceRepresentation.AGENT_ID,
					mappingServiceIdRepresentation, amo.getId());
		} else {
			amo.setName(MappingServiceRepresentation.AGENT_NAME);
			amo.setType(MappingServiceRepresentation.AGENT_TYPE);
			amo.set(new Agent());
			HashMap<String, String> agentFragments = new HashMap<>();
			agentFragments.put("name", "Dynamic Data Mapper");
			agentFragments.put("version", version);
			agentFragments.put("url", "https://github.com/SoftwareAG/cumulocity-dynamic-mapper");
			agentFragments.put("maintainer", "Open-Source");
			amo.set(agentFragments, "c8y_Agent");
			amo.set(new IsDevice());
			amo.setProperty(C8YAgent.MAPPING_FRAGMENT,
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

	public void createExtensibleProcessor(String tenant) {
		ExtensibleProcessor extensibleProcessor = new ExtensibleProcessor(configurationRegistry);
		configurationRegistry.getExtensibleProcessors().put(tenant, extensibleProcessor);
		log.debug("Tenant {} - Create ExtensibleProcessor {}", tenant, extensibleProcessor);

		// check if managedObject for internal mapping extension exists
		List<ManagedObjectRepresentation> internalExtension = extensionsComponent.getInternal();
		ManagedObjectRepresentation ie = new ManagedObjectRepresentation();
		if (internalExtension == null || internalExtension.size() == 0) {
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
		log.debug("Tenant {} - Internal extension: {} registered: {}", tenant,
				ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
				ie.getId().getValue(), ie);
	}

	public void sendNotificationLifecycle(String tenant, ConnectorStatus connectorStatus, String message) {
		if (configurationRegistry.getServiceConfigurations().get(tenant).sendNotificationLifecycle
				&& !(connectorStatus.equals(previousConnectorStatus))) {
			previousConnectorStatus = connectorStatus;
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date now = new Date();
			String date = dateFormat.format(now);
			Map<String, String> stMap = Map.ofEntries(
					entry("status", connectorStatus.name()),
					entry("message",
							message == null ? C8Y_NOTIFICATION_CONNECTOR + ": " + connectorStatus.name() : message),
					entry("connectorName", C8Y_NOTIFICATION_CONNECTOR),
					entry("connectorIdent", "000000"),
					entry("date", date));
			createEvent("Connector status:" + connectorStatus.name(),
					C8YAgent.STATUS_NOTIFICATION_EVENT_TYPE,
					DateTime.now(), configurationRegistry.getMappingServiceRepresentations().get(tenant), tenant,
					stMap);
		}
	}

	public MicroserviceCredentials removeAppKeyHeaderFromContext(MicroserviceCredentials context) {
		final MicroserviceCredentials clonedContext = new MicroserviceCredentials(
				context.getTenant(),
				context.getUsername(), context.getPassword(),
				context.getOAuthAccessToken(), context.getXsrfToken(),
				context.getTfaToken(), null);
		return clonedContext;
	}

	public void initializeInboundExternalIdCache(String tenant, int inboundExternalIdCacheSize) {
		log.info("Tenant {} - Initialize cache {}", tenant, inboundExternalIdCacheSize);
		inboundExternalIdCaches.put(tenant, new InboundExternalIdCache(inboundExternalIdCacheSize, tenant));
	}

	public InboundExternalIdCache deleteInboundExternalIdCache(String tenant) {
		return inboundExternalIdCaches.remove(tenant);
	}

	public InboundExternalIdCache getInboundExternalIdCache(String tenant) {
		return inboundExternalIdCaches.get(tenant);
	}

	public void clearInboundExternalIdCache(String tenant, boolean recreate, int inboundExternalIdCacheSize) {
		InboundExternalIdCache inboundExternalIdCache = inboundExternalIdCaches.get(tenant);
		if (inboundExternalIdCache != null) {
			// FIXME Recreating the cache creates a new instance of InboundExternalIdCache
			// which causes issues with Metering
			if (recreate) {
				inboundExternalIdCaches.put(tenant, new InboundExternalIdCache(inboundExternalIdCacheSize, tenant));
			} else {
				inboundExternalIdCache.clearCache();
			}
		}
	}

	public int getSizeInboundExternalIdCache(String tenant) {
		InboundExternalIdCache inboundExternalIdCache = inboundExternalIdCaches.get(tenant);
		if (inboundExternalIdCache != null) {
			return inboundExternalIdCache.getCacheSize();
		} else
			return 0;
	}

}