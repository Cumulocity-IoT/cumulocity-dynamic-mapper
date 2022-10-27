package mqtt.mapping.configuration;

import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConfigurationService {
    private static final String OPTION_CATEGORY_CONFIGURATION = "mqttclient.configuration";

    private static final String OPTION_KEY_CONNECTION_CONFIGURATION = "credentials.mqttclient.connection.configuration";
    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    public ConfigurationService(final TenantOptionApi tenantOptionApi) {
        this.tenantOptionApi = tenantOptionApi;
    }

    public void saveConnectionConfiguration(final ConnectionConfiguration configuration) throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = new OptionRepresentation();
        optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionRepresentation.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        optionRepresentation.setValue(configurationJson);

        tenantOptionApi.save(optionRepresentation);
    }

    public void saveServiceConfiguration(final ServiceConfiguration configuration) throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = new OptionRepresentation();
        optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionRepresentation.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        optionRepresentation.setValue(configurationJson);

        tenantOptionApi.save(optionRepresentation);
    }

    public ConnectionConfiguration loadConnectionConfiguration() {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ConnectionConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(), ConnectionConfiguration.class);
            log.debug("Returning connection configuration found: {}:", configuration.mqttHost );
            return configuration;
        } catch (SDKException exception) {
            log.error("No configuration found, returning empty element!");
            //exception.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public ServiceConfiguration loadServiceConfiguration() {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ServiceConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(), ServiceConfiguration.class);
            log.debug("Returning service configuration found: {}:", configuration.logPayload );
            return configuration;
        } catch (SDKException exception) {
            log.warn("No configuration found, returning empty element!");
            return new ServiceConfiguration(false);
            //exception.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteAllConfiguration() {
        final OptionPK optionPK = new OptionPK();
        optionPK.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionPK.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
        optionPK.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }

    public ConnectionConfiguration setConfigurationActive(boolean active) {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ConnectionConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(), ConnectionConfiguration.class);
            configuration.active = active;
            log.debug("Setting connection: {}:", configuration.active );
            final String configurationJson = new ObjectMapper().writeValueAsString(configuration);
            optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
            optionRepresentation.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
            optionRepresentation.setValue(configurationJson);
            tenantOptionApi.save(optionRepresentation);
            return configuration;
        } catch (SDKException exception) {
            log.warn("No configuration found, returning empty element!");
            //exception.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }
}
