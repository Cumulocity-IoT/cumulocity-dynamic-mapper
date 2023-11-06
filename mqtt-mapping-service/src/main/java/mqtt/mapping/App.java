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

package mqtt.mapping;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;
import com.cumulocity.microservice.context.annotation.EnableContextSupport;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.model.DateTimeConverter;
import com.cumulocity.model.IDTypeConverter;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.BaseResourceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.connector.core.registry.ConnectorRegistry;
import mqtt.mapping.connector.core.registry.ConnectorRegistryException;
import mqtt.mapping.connector.core.client.IConnectorClient;
import mqtt.mapping.connector.mqtt.MQTTClient;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.model.*;
import mqtt.mapping.notification.C8YAPISubscriber;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.InnerNodeSerializer;
import mqtt.mapping.processor.extension.ExtensibleProcessorInbound;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.inbound.FlatFileProcessor;
import mqtt.mapping.processor.inbound.GenericBinaryProcessor;
import mqtt.mapping.processor.inbound.JSONProcessor;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.outbound.BasePayloadProcessorOutbound;
import mqtt.mapping.processor.outbound.JSONProcessorOutbound;
import mqtt.mapping.processor.processor.fixed.StaticProtobufProcessor;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.svenson.AbstractDynamicProperties;
import org.svenson.JSONParser;
import org.svenson.converter.DefaultTypeConverterRepository;

import java.io.IOException;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@MicroserviceApplication
@EnableContextSupport
@SpringBootApplication
@EnableAsync
@Slf4j
public class App {

    @Autowired
    C8YAPISubscriber notificationSubscriber;

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        return executor;
    }

    @Bean("cachedThreadPool")
    public ExecutorService cachedThreadPool() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = baseObjectMapper();
        objectMapper.registerModule(cumulocityModule());
        SimpleModule module = new SimpleModule();
        module.addSerializer(InnerNode.class, new InnerNodeSerializer());
        objectMapper.registerModule(module);
        return objectMapper;
    }

    @Bean("payloadProcessorsInbound")
    public Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound(ObjectMapper objectMapper,
                                                                              @Lazy IConnectorClient connectorClient,
                                                                              @Lazy C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessor(objectMapper, connectorClient, c8yAgent, tenant),
                MappingType.FLAT_FILE, new FlatFileProcessor(objectMapper, connectorClient, c8yAgent, tenant),
                MappingType.GENERIC_BINARY, new GenericBinaryProcessor(objectMapper, connectorClient, c8yAgent, tenant),
                MappingType.PROTOBUF_STATIC, new StaticProtobufProcessor(objectMapper, connectorClient, c8yAgent, tenant),
                MappingType.PROCESSOR_EXTENSION, new ExtensibleProcessorInbound(objectMapper, connectorClient, c8yAgent, tenant));
    }

    @Bean("payloadProcessorsOutbound")
    public Map<MappingType, BasePayloadProcessorOutbound<?>> payloadProcessorsOutbound(ObjectMapper objectMapper, IConnectorClient connectorClient,
                                                                                       C8YAgent c8yAgent, String tenant) {
        return Map.of(
                MappingType.JSON, new JSONProcessorOutbound(objectMapper, connectorClient, c8yAgent, tenant));
    }

    public static ObjectMapper baseObjectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(NON_NULL);
        // objectMapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
        // objectMapper.configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true);
        // objectMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        //objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        //objectMapper.setDateFormat(new RFC3339DateFormat());
        //objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new JodaModule());

        return objectMapper;
    }

    private static Module cumulocityModule() {
        final JSONParser jsonParser = getJsonParser();
        final ObjectMapper mapper = baseObjectMapper();

        class SvensonDeserializers extends Deserializers.Base {
            public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config,
                                                            BeanDescription beanDesc) {
                final Class<?> rawClass = type.getRawClass();

                // base resource representation is deserialized using svenson
                if (BaseResourceRepresentation.class.isAssignableFrom(rawClass)) {
                    return new JsonDeserializer<Object>() {
                        public Object deserialize(JsonParser p, DeserializationContext context) throws IOException {
                            final ObjectNode root = mapper.readTree(p);
                            final String json = String.valueOf(root);
                            return jsonParser.parse(rawClass, json);
                        }
                    };
                }

                return null;
            }

        }

        class SvensonSerializers extends Serializers.Base {
            @Override
            public JsonSerializer<?> findSerializer(final SerializationConfig config, final JavaType type,
                                                    BeanDescription beanDesc) {
                final Class<?> rawClass = type.getRawClass();

                // gid is serialized using svenson
                if (GId.class.isAssignableFrom(rawClass)) {
                    return new JsonSerializer<Object>() {
                        @SneakyThrows
                        public void serialize(Object value, final JsonGenerator gen,
                                              final SerializerProvider serializers) {
                            final GId representation = (GId) value;
                            gen.writeString(representation.getValue());
                        }
                    };
                }

                return null;
            }
        }

        return new SimpleModule() {
            public void setupModule(SetupContext setupContext) {
                setupContext.addDeserializers(new SvensonDeserializers());
                setupContext.addSerializers(new SvensonSerializers());
                // fragments in extensible representations are serialized using jackson
                setupContext.setMixInAnnotations(AbstractExtensibleRepresentation.class,
                        AbstractExtensibleRepresentationMixIn.class);
                setupContext.setMixInAnnotations(AbstractDynamicProperties.class,
                        AbstractExtensibleRepresentationMixIn.class);
            }
        };
    }

    private static JSONParser getJsonParser() {
        final JSONParser jsonParser = JSONBase.getJSONParser();
        jsonParser.registerTypeConversion(DateTime.class, new DateTimeConverter());
        jsonParser.registerTypeConversion(GId.class, new IDTypeConverter() {
            public Object fromJSON(Object in) {
                if (in instanceof Number) {
                    // gid is serialized using svenson
                    return GId.asGId(((Number) in).longValue());
                }
                return super.fromJSON(in);
            }
        });
        // cleaning the cache
        jsonParser.setTypeConverterRepository(new DefaultTypeConverterRepository());
        return jsonParser;
    }

    public interface AbstractExtensibleRepresentationMixIn {
        @JsonAnyGetter
        Map<String, Object> getAttrs();

        @JsonAnySetter
        void setProperty(String name, Object value);
    }

    @EventListener
    public void destroy(MicroserviceSubscriptionRemovedEvent event) {
        log.info("Microservice unsubscribed for tenant {}", event.getTenant());
        String tenant = event.getTenant();
        notificationSubscriber.disconnect(null);
        try {
            connectorRegistry.unregisterAllClientsForTenant(tenant);
        } catch (ConnectorRegistryException e) {
            log.error("Error on cleaning up connector clients");
        }
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        //Executed for each tenant subscribed
        String tenant = event.getCredentials().getTenant();
        MicroserviceCredentials credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation mappingServiceMOR = c8YAgent.createMappingObject(tenant);
        c8YAgent.checkExtensions();
        notificationSubscriber.init();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.loadServiceConfiguration();
        //loadProcessorExtensions();
        MappingServiceRepresentation mappingServiceRepresentation = baseObjectMapper().convertValue(mappingServiceMOR, MappingServiceRepresentation.class);
        mappingComponent.initializeMappingComponent(tenant, mappingServiceRepresentation);

        try {
            if (serviceConfiguration != null) {
                //TODO Add other clients - maybe dynamically per tenant
                MQTTClient mqttClient = new MQTTClient();
                mqttClient.setTenantId(tenant);
                connectorRegistry.registerClient(tenant, mqttClient);
                mqttClient.submitInitialize();
                mqttClient.submitConnect();
                mqttClient.runHouskeeping();
            }

        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            //mqttClient.submitConnect();
        }
    }


    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

}
