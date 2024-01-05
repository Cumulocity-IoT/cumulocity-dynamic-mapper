package dynamic.mapping.core;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YAPISubscriber;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import lombok.Getter;

@Component
public class ConfigurationRegistry {

    @Getter
    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations = new HashMap<>();

    @Getter
    private Map<String, ServiceConfiguration> serviceConfigurations = new HashMap<>();

    // structure: <tenant, <extensibleProcessorInbound>>
    @Getter
    private Map<String, ExtensibleProcessorInbound> extensibleProcessors = new HashMap<>();

    @Getter
    @Autowired
    private ObjectMapper objectMapper;

    @Getter
    private C8YAgent c8yAgent;

    @Autowired
    public void setC8yAgent(C8YAgent c8yAgent){
        this.c8yAgent = c8yAgent;
    }

    @Getter
    private C8YAPISubscriber notificationSubscriber;
    @Autowired
        public void setC8yAgent(C8YAPISubscriber notificationSubscriber){
        this.notificationSubscriber = notificationSubscriber;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
}
