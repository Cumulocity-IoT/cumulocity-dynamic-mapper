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

package dynamic.mapping.processor.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.codec.binary.Hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * AsynchronousDispatcherInbound
 * 
 * This class implements the <code>GenericMessageCallback</code> which is then
 * registered as a listener when new messages arrive.
 * It processes INBOUND messages and works asynchronously.
 * A task <code>GenericMessageCallback.MappingInboundTask</code> is added the
 * ExecutorService, to not block new arriving messages.
 * The call method in
 * <code>AsynchronousDispatcherInbound.MappingInboundTask</code> is the core of
 * the message processing.
 * For all resolved mappings the following steps are performed for new
 * messages:
 * ** deserialize the payload
 * ** extract the content from the payload based on the defined substitution in
 * the mapping and add these to a post processing cache
 * ** substitute in the defined target template of the mapping the extracted
 * content from the cache
 * ** send the resulting target payload to Cumulocity
 */

@Slf4j
public class AsynchronousDispatcherInbound implements GenericMessageCallback {

	private AConnectorClient connectorClient;

	private ExecutorService cachedThreadPool;

	private MappingComponent mappingComponent;

	private ConfigurationRegistry configurationRegistry;

	public AsynchronousDispatcherInbound(ConfigurationRegistry configurationRegistry,
			AConnectorClient connectorClient) {
		this.connectorClient = connectorClient;
		this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
		this.mappingComponent = configurationRegistry.getMappingComponent();
		this.configurationRegistry = configurationRegistry;
	}

	public static class MappingInboundTask<T> implements Callable<List<ProcessingContext<?>>> {
		List<Mapping> resolvedMappings;
		Map<MappingType, BasePayloadProcessorInbound<?>> payloadProcessorsInbound;
		ConnectorMessage connectorMessage;
		MappingComponent mappingComponent;
		C8YAgent c8yAgent;
		ObjectMapper objectMapper;
		ServiceConfiguration serviceConfiguration;
		Timer inboundProcessingTimer;
		Counter inboundProcessingCounter;
		AConnectorClient connectorClient;

		public MappingInboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
				ConnectorMessage message, AConnectorClient connectorClient) {
			this.connectorClient = connectorClient;
			this.resolvedMappings = resolvedMappings;
			this.mappingComponent = configurationRegistry.getMappingComponent();
			this.c8yAgent = configurationRegistry.getC8yAgent();
			this.payloadProcessorsInbound = configurationRegistry.getPayloadProcessorsInbound()
					.get(message.getTenant());
			this.connectorMessage = message;
			this.objectMapper = configurationRegistry.getObjectMapper();
			this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(message.getTenant());
			this.inboundProcessingTimer = Timer.builder("dynmapper_inbound_processing_time")
					.tag("tenant", connectorMessage.getTenant()).tag("connector", connectorMessage.getConnectorIdent())
					.description("Processing time of inbound messages").register(Metrics.globalRegistry);
			this.inboundProcessingCounter = Counter.builder("dynmapper_inbound_message_total")
					.tag("tenant", connectorMessage.getTenant()).description("Total number of inbound messages")
					.tag("connector", connectorMessage.getConnectorIdent()).register(Metrics.globalRegistry);

		}

		@Override
		public List<ProcessingContext<?>> call() throws Exception {
			// long startTime = System.nanoTime();
			Timer.Sample timer = Timer.start(Metrics.globalRegistry);
			String tenant = connectorMessage.getTenant();
			String topic = connectorMessage.getTopic();
			boolean sendPayload = connectorMessage.isSendPayload();

			List<ProcessingContext<?>> processingResult = new ArrayList<>();
			MappingStatus mappingStatusUnspecified = mappingComponent
					.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
			resolvedMappings.forEach(mapping -> {
				// only process active mappings
				if (mapping.isActive() && connectorClient.getMappingsDeployedInbound().containsKey(mapping.ident)) {
					MappingStatus mappingStatus = mappingComponent.getMappingStatus(tenant, mapping);

					ProcessingContext<?> context;
					if (mapping.mappingType.payloadType.equals(String.class)) {
						context = new ProcessingContext<String>();
					} else {
						context = new ProcessingContext<byte[]>();
					}
					context.setTopic(topic);
					context.setMappingType(mapping.mappingType);
					context.setMapping(mapping);
					context.setSendPayload(sendPayload);
					context.setTenant(tenant);
					context.setSupportsMessageContext(
							connectorMessage.isSupportsMessageContext() && mapping.supportsMessageContext);
					context.setKey(connectorMessage.getKey());
					context.setServiceConfiguration(serviceConfiguration);
					// identify the correct processor based on the mapping type
					MappingType mappingType = context.getMappingType();
					BasePayloadProcessorInbound processor = payloadProcessorsInbound.get(mappingType);

					if (processor != null) {
						try {
							inboundProcessingCounter.increment();
							processor.deserializePayload(context, connectorMessage);
							if (serviceConfiguration.logPayload || mapping.debug) {
								log.info("Tenant {} - New message on topic: {}, on connector: {}, wrapped message: {}",
										tenant,
										context.getTopic(),
										connectorClient.getConnectorIdent(),
										context.getPayload().toString());
							} else {
								log.info("Tenant {} - New message on topic: {}, on connector: {}", tenant,
										context.getTopic(), connectorClient.getConnectorIdent());
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
											mapping.mappingTopic,
											mapping.snoopedTemplates.size(),
											mapping.snoopStatus);
									mappingComponent.addDirtyMapping(tenant, mapping);

								} else {
									log.warn(
											"Tenant {} - Message could NOT be parsed, ignoring this message, as class is not valid: {} {}",
											tenant,
											context.getPayload().getClass());
								}
							} else {
								processor.extractFromSource(context);
								processor.applyFiler(context);
								if (!context.isIgnoreFurtherProcessing()) {
									processor.substituteInTargetAndSend(context);
									List<C8YRequest> resultRequests = context.getRequests();
									if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
										mappingStatus.errors++;
									}
								}
							}
						} catch (Exception e) {
							log.warn("Tenant {} - Message could NOT be parsed, ignoring this message: {}", tenant,
									e.getMessage());
							log.debug("Tenant {} - Message Stacktrace: ", tenant, e);
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
			timer.stop(inboundProcessingTimer);

			return processingResult;
		}
	}

	public Future<List<ProcessingContext<?>>> processMessage(ConnectorMessage message) {
		String topic = message.getTopic();
		String tenant = message.getTenant();

		MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
		Future<List<ProcessingContext<?>>> futureProcessingResult = null;
		List<Mapping> resolvedMappings = new ArrayList<>();

		if (topic != null && !topic.startsWith("$SYS")) {
			if (message.getPayload() != null) {
				try {
					resolvedMappings = mappingComponent.resolveMappingInbound(tenant, topic);
				} catch (Exception e) {
					log.warn(
							"Tenant {} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
							tenant, topic);
					log.debug(e.getMessage(), e);
					mappingStatusUnspecified.errors++;
				}
			} else {
				return futureProcessingResult;
			}
		} else {
			return futureProcessingResult;
		}

		futureProcessingResult = cachedThreadPool.submit(
				new MappingInboundTask(configurationRegistry, resolvedMappings,
						message, connectorClient));

		return futureProcessingResult;

	}

	@Override
	public void onClose(String closeMessage, Throwable closeException) {
	}

	@Override
	public void onMessage(ConnectorMessage message) {
		processMessage(message);
	}

	@Override
	public void onError(Throwable errorException) {
	}
}