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

package dynamic.mapping.processor.outbound;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.API;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.notification.websocket.Notification;
import dynamic.mapping.processor.C8YMessage;
import org.apache.commons.codec.binary.Hex;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * AsynchronousDispatcherOutbound
 * 
 * This class implements the <code>NotificationCallback</code> which is then
 * registered as a listener in the <code>C8YNotificationSubscriber </code> when
 * new messages arrive.
 * It processes OUTBOUND messages and works asynchronously.
 * A task <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is
 * added the ExecutorService, to not block new arriving messages.
 * The call method in
 * <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is the core
 * of the message processing.
 * For all resolved mappings the following steps are performed for new
 * messages:
 * ** deserialize the payload
 * ** extract the content from the payload based on the defined substitution in
 * the mapping and add these to a post processing cache
 * ** substitute in the defined target template of the mapping the extracted
 * content from the cache
 * ** send the resulting target payload to connectorClient, e.g. MQTT broker
 */
@Slf4j
public class AsynchronousDispatcherOutbound implements NotificationCallback {

	@Getter
	protected AConnectorClient connectorClient;

	protected C8YNotificationSubscriber notificationSubscriber;

	protected C8YAgent c8yAgent;

	protected ObjectMapper objectMapper;

	protected ExecutorService cachedThreadPool;

	protected MappingComponent mappingComponent;

	protected ConfigurationRegistry configurationRegistry;

	protected Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound;

	// The Outbound Dispatcher is hardly connected to the Connector otherwise it is
	// not possible to correlate messages received bei Notification API to the
	// correct Connector
	public AsynchronousDispatcherOutbound(ConfigurationRegistry configurationRegistry,
			AConnectorClient connectorClient) {
		this.objectMapper = configurationRegistry.getObjectMapper();
		this.c8yAgent = configurationRegistry.getC8yAgent();
		this.mappingComponent = configurationRegistry.getMappingComponent();
		this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
		this.connectorClient = connectorClient;
		// log.info("Tenant {} - HIER I {} {}", connectorClient.getTenant(),
		// configurationRegistry.getPayloadProcessorsOutbound());
		// log.info("Tenant {} - HIER II {} {}", connectorClient.getTenant(),
		// configurationRegistry.getPayloadProcessorsOutbound().get(connectorClient.getTenant()));
		this.payloadProcessorsOutbound = configurationRegistry.getPayloadProcessorsOutbound()
				.get(connectorClient.getTenant())
				.get(connectorClient.getConnectorIdent());
		this.configurationRegistry = configurationRegistry;
		this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();

	}

	@Override
	public void onOpen(URI serverUri) {
		log.info("Tenant {} - Connector {} connected to Cumulocity notification service over Web Socket",
				connectorClient.getTenant(), connectorClient.getConnectorName());
		notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
	}

	@Override
	public void onNotification(Notification notification) {
		// We don't care about UPDATES nor DELETES and ignore notifications if connector
		// is not connected
		if ("CREATE".equals(notification.getNotificationHeaders().get(1)) && connectorClient.isConnected()) {
			String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
			// log.info("Tenant {} - Notification received: <{}>, <{}>, <{}>, <{}>", tenant,
			// notification.getMessage(),
			// notification.getNotificationHeaders(),
			// connectorClient.connectorConfiguration.name,
			// connectorClient.isConnected());
			C8YMessage c8yMessage = new C8YMessage();
			c8yMessage.setPayload(notification.getMessage());
			c8yMessage.setApi(notification.getApi());
			c8yMessage.setTenant(tenant);
			c8yMessage.setSendPayload(true);
			processMessage(c8yMessage);
		}
	}

	@Override
	public void onError(Throwable t) {
		log.error("Tenant {} - We got an exception: ", connectorClient.getTenant(), t);
	}

	@Override
	public void onClose(int statusCode, String reason) {
		log.info("Tenant {} - Web Socket connection closed.", connectorClient.getTenant());
		if (reason.contains("401"))
			notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
		else
			notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
	}

	public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
		return notificationHeaders.get(0).split("/")[1];
	}

	public static class MappingOutboundTask<T> implements Callable<List<ProcessingContext<?>>> {
		List<Mapping> resolvedMappings;
		Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound;
		C8YMessage c8yMessage;
		MappingComponent mappingStatusComponent;
		C8YAgent c8yAgent;
		ObjectMapper objectMapper;
		ServiceConfiguration serviceConfiguration;
		AConnectorClient connectorClient;

		public MappingOutboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
				MappingComponent mappingStatusComponent,
				Map<MappingType, BasePayloadProcessorOutbound<T>> payloadProcessorsOutbound,
				C8YMessage c8yMessage, AConnectorClient connectorClient) {
			this.connectorClient = connectorClient;
			this.resolvedMappings = resolvedMappings;
			this.mappingStatusComponent = mappingStatusComponent;
			this.c8yAgent = configurationRegistry.getC8yAgent();
			this.payloadProcessorsOutbound = payloadProcessorsOutbound;
			this.c8yMessage = c8yMessage;
			this.objectMapper = configurationRegistry.getObjectMapper();
			this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(c8yMessage.getTenant());

		}

		@Override
		public List<ProcessingContext<?>> call() throws Exception {
			long startTime = System.nanoTime();

			Timer.Sample timer = Timer.start(Metrics.globalRegistry);
			String tenant = c8yMessage.getTenant();
			boolean sendPayload = c8yMessage.isSendPayload();

			List<ProcessingContext<?>> processingResult = new ArrayList<>();
			MappingStatus mappingStatusUnspecified = mappingStatusComponent
					.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
			resolvedMappings.forEach(mapping -> {
				// only process active mappings
				if (mapping.isActive() && connectorClient.getMappingsDeployedOutbound().containsKey(mapping.ident)) {
					MappingStatus mappingStatus = mappingStatusComponent.getMappingStatus(tenant, mapping);

					ProcessingContext<?> context;
					if (mapping.mappingType.payloadType.equals(String.class)) {
						context = new ProcessingContext<String>();
					} else {
						context = new ProcessingContext<byte[]>();
					}
					context.setTopic(mapping.publishTopic);
					context.setMappingType(mapping.mappingType);
					context.setMapping(mapping);
					context.setSupportsMessageContext(mapping.supportsMessageContext);
					;
					context.setSendPayload(sendPayload);
					context.setTenant(tenant);
					context.setQos(mapping.getQos());
					context.setServiceConfiguration(serviceConfiguration);
					// identify the correct processor based on the mapping type
					MappingType mappingType = context.getMappingType();
					BasePayloadProcessorOutbound processor = payloadProcessorsOutbound.get(mappingType);

					if (processor != null) {
						try {
							processor.deserializePayload(context, c8yMessage);
							if (serviceConfiguration.logPayload || mapping.debug) {
								log.info(
										"Tenant {} - New message for topic: {}, for connector: {}, wrapped message: {}",
										tenant,
										context.getTopic(),
										connectorClient.getConnectorIdent(),
										context.getPayload().toString());
							} else {
								log.info("Tenant {} - New message for topic: {}, for connector: {}, sendPayload: {}",
										tenant,
										context.getTopic(), connectorClient.getConnectorIdent(), sendPayload);
							}
							mappingStatus.messagesReceived++;
							if (mapping.snoopStatus == SnoopStatus.ENABLED
									|| mapping.snoopStatus == SnoopStatus.STARTED) {
								String serializedPayload = null;
								if (context.getPayload() instanceof JsonNode) {
									serializedPayload = objectMapper
											.writeValueAsString((JsonNode) context.getPayload());
								} else if (context.getPayload() instanceof String) {
									serializedPayload = (String) context.getPayload();
								}
								if (context.getPayload() instanceof byte[]) {
									serializedPayload = Hex.encodeHexString((byte[]) context.getPayload());
								}

								if (serializedPayload != null) {
									mapping.addSnoopedTemplate(serializedPayload);
									mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
									mappingStatus.snoopedTemplatesActive++;

									log.debug("Tenant {} - Adding snoopedTemplate to map: {},{},{}", tenant,
											mapping.subscriptionTopic,
											mapping.snoopedTemplates.size(),
											mapping.snoopStatus);
									mappingStatusComponent.addDirtyMapping(tenant, mapping);

								} else {
									log.warn(
											"Tenant {} - Message could NOT be parsed, ignoring this message, as class is not valid: {}",
											tenant,
											context.getPayload().getClass());
								}
							} else {
								processor.extractFromSource(context);
								processor.substituteInTargetAndSend(context);
								Counter.builder("dynmapper_outbound_message_total")
										.tag("tenant", c8yMessage.getTenant())
										.description("Total number of outbound messages")
										.tag("connector", processor.connectorClient.getConnectorIdent())
										.register(Metrics.globalRegistry).increment();
								timer.stop(Timer.builder("dynmapper_outbound_processing_time")
										.tag("tenant", c8yMessage.getTenant())
										.tag("connector", processor.connectorClient.getConnectorIdent())
										.description("Processing time of outbound messages")
										.register(Metrics.globalRegistry));

								List<C8YRequest> resultRequests = context.getRequests();
								if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
									mappingStatus.errors++;
								}
							}
						} catch (Exception e) {
							log.warn("Tenant {} - Message could NOT be parsed, ignoring this message: {}", tenant,
									e.getMessage());
							log.error("Tenant {} - Message Stacktrace: ", tenant, e);
							mappingStatus.errors++;
						}
					} else {
						mappingStatusUnspecified.errors++;
						log.error("Tenant {} - No process for MessageType: {} registered, ignoring this message!",
								tenant, mappingType);
					}
					processingResult.add(context);
				}
			});
			return processingResult;
		}

	}

	public Future<List<ProcessingContext<?>>> processMessage(C8YMessage c8yMessage) {
		String tenant = c8yMessage.getTenant();
		MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
		Future<List<ProcessingContext<?>>> futureProcessingResult = null;
		List<Mapping> resolvedMappings = new ArrayList<>();

		// Handle C8Y Operation Status
		// TODO Add OperationAutoAck Status to activate/deactive
		OperationRepresentation op = null;
		//
		if (c8yMessage.getApi().equals(API.OPERATION)) {
			op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
		}
		if (c8yMessage.getPayload() != null) {
			try {
				JsonNode message = objectMapper.readTree(c8yMessage.getPayload());
				resolvedMappings = mappingComponent.resolveMappingOutbound(tenant, message, c8yMessage.getApi());
				if (resolvedMappings.size() > 0 && op != null)
					c8yAgent.updateOperationStatus(tenant, op, OperationStatus.EXECUTING, null);
			} catch (Exception e) {
				log.warn("Tenant {} - Error resolving appropriate map. Could NOT be parsed. Ignoring this message!",
						tenant);
				log.debug(e.getMessage(), tenant);
				// if (op != null)
				// c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
				// e.getLocalizedMessage());
				mappingStatusUnspecified.errors++;
			}
		} else {
			return futureProcessingResult;
		}

		futureProcessingResult = cachedThreadPool.submit(
				new MappingOutboundTask(configurationRegistry, resolvedMappings, mappingComponent,
						payloadProcessorsOutbound, c8yMessage, connectorClient));

		if (op != null) {
			// Blocking for Operations to receive the processing result to update operation
			// status
			try {
				List<ProcessingContext<?>> results = futureProcessingResult.get();
				if (results.size() > 0) {
					if (results.get(0).hasError()) {
						c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
								results.get(0).getErrors().toString());
					} else {
						c8yAgent.updateOperationStatus(tenant, op, OperationStatus.SUCCESSFUL, null);
					}
				} else {
					// No Mapping found
					// c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
					// "No Mapping found for operation " + op.toJSON());
				}
			} catch (InterruptedException e) {
				// c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
				// e.getLocalizedMessage());
			} catch (ExecutionException e) {
				// c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED,
				// e.getLocalizedMessage());
			}
		}
		return futureProcessingResult;
	}
}