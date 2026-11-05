package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.DeploymentMapEntryExpanded;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;

class DeploymentControllerTest {

    private MappingService mappingService;
    private ConnectorConfigurationService connectorConfigurationService;
    private DeploymentController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ContextService<UserCredentials> contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn("tenantA");
        when(contextService.getContext()).thenReturn(credentials);
        mappingService = mock(MappingService.class);
        connectorConfigurationService = mock(ConnectorConfigurationService.class);
        controller = new DeploymentController(mock(ConnectorRegistry.class), mappingService,
                mock(dynamic.mapper.mapping.MappingOrchestrationService.class),
                connectorConfigurationService, contextService);
    }

    @Test
    void definedWithoutExpandKeepsTheIdentifierOnlyShape() {
        Map<String, List<String>> map = Map.of("m1", List.of("c1"));
        when(mappingService.getDeploymentMap("tenantA")).thenReturn(map);

        ResponseEntity<?> response = controller.getDeploymentMap(false);

        assertEquals(map, response.getBody());
    }

    @Test
    @SuppressWarnings("unchecked")
    void definedWithExpandResolvesNamesAndMarksVanishedOnesAsNull() {
        when(mappingService.getDeploymentMap("tenantA"))
                .thenReturn(Map.of("m1", List.of("c1", "gone"), "orphan", List.of("c1")));
        Mapping mapping = Mapping.builder().identifier("m1").name("Temperature").direction(Direction.INBOUND).build();
        when(mappingService.getMappings("tenantA", null)).thenReturn(List.of(mapping));
        ConnectorConfiguration connector = new ConnectorConfiguration();
        connector.setIdentifier("c1");
        connector.setName("Broker");
        when(connectorConfigurationService.getConnectorConfigurations("tenantA")).thenReturn(List.of(connector));

        Map<String, DeploymentMapEntryExpanded> body = (Map<String, DeploymentMapEntryExpanded>) controller
                .getDeploymentMap(true).getBody();

        DeploymentMapEntryExpanded m1 = body.get("m1");
        assertEquals("Temperature", m1.name());
        assertEquals(Direction.INBOUND, m1.direction());
        assertEquals("Broker", m1.connectors().get(0).name());
        assertNull(m1.connectors().get(1).name());
        assertEquals("gone", m1.connectors().get(1).identifier());
        assertNull(body.get("orphan").name());
        assertNull(body.get("orphan").direction());
    }
}
