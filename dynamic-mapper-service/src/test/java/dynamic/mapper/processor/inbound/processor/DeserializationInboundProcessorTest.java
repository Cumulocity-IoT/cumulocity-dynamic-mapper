package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeserializationInboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private ConnectorMessage connectorMessage;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private MappingStatus unspecifiedMappingStatus;

    @BeforeEach
    void setUp() throws Exception {
        // Create real Mapping object with proper initialization
        mapping = new Mapping();
        mapping.id = "test-mapping-id";
        mapping.identifier = "test-mapping";
        mapping.name = "Test Mapping";
        
        // Create real MappingStatus objects
        mappingStatus = new MappingStatus(
            "test-id", "Test Mapping", "test-mapping", Direction.INBOUND,
            "test/topic", "output/topic", 0L, 0L, 0L, 0L, 0L, null
        );
        
        unspecifiedMappingStatus = new MappingStatus(
            "unspec-id", "Unspecified Mapping", "UNSPECIFIED", Direction.INBOUND,
            "#", "#", 0L, 0L, 0L, 0L, 0L, null
        );
        
        // Setup basic exchange and message mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("tenant", String.class)).thenReturn(TEST_TENANT);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
        when(message.getHeader("connectorMessage", ConnectorMessage.class)).thenReturn(connectorMessage);
        
        // Setup mapping status mocks
        when(mappingService.getMappingStatus(any(String.class), any(Mapping.class))).thenReturn(mappingStatus);
        when(mappingService.getMappingStatus(TEST_TENANT, Mapping.UNSPECIFIED_MAPPING)).thenReturn(unspecifiedMappingStatus);
    }

    private void setupValidPayload(MappingType mappingType) {
        switch (mappingType) {
            case JSON:
            case CODE_BASED:
                // Provide valid JSON
                when(connectorMessage.getPayload()).thenReturn("{\"temperature\": 25.5, \"deviceId\": \"sensor001\"}".getBytes());
                break;
            case FLAT_FILE:
                // Provide valid flat file data
                when(connectorMessage.getPayload()).thenReturn("25.5,sensor001,active".getBytes());
                break;
            case HEX:
                // Provide valid hex data
                when(connectorMessage.getPayload()).thenReturn("48656c6c6f".getBytes());
                break;
            case PROTOBUF_INTERNAL:
            case EXTENSION_SOURCE:
            case EXTENSION_SOURCE_TARGET:
                // Provide valid binary data
                when(connectorMessage.getPayload()).thenReturn(new byte[]{0x08, 0x74, 0x01, 0x12, 0x04, 0x74, 0x65, 0x73, 0x74});
                break;
            default:
                // Default to valid JSON
                when(connectorMessage.getPayload()).thenReturn("{\"data\": \"test\"}".getBytes());
        }
    }

    @Test
    void testProcessJsonMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.JSON;
        setupValidPayload(MappingType.JSON);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessProtobufInternalMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.PROTOBUF_INTERNAL;
        setupValidPayload(MappingType.PROTOBUF_INTERNAL);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessExtensionSourceMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.EXTENSION_SOURCE;
        setupValidPayload(MappingType.EXTENSION_SOURCE);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessExtensionSourceTargetMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.EXTENSION_SOURCE_TARGET;
        setupValidPayload(MappingType.EXTENSION_SOURCE_TARGET);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessFlatFileMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.FLAT_FILE;
        setupValidPayload(MappingType.FLAT_FILE);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessHexMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.HEX;
        setupValidPayload(MappingType.HEX);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessCodeBasedMappingTypeSuccess() throws Exception {
        // Given
        mapping.mappingType = MappingType.CODE_BASED;
        setupValidPayload(MappingType.CODE_BASED);
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    @Test
    void testProcessWithNullMappingType() throws Exception {
        // Given
        mapping.mappingType = null;
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, Mapping.UNSPECIFIED_MAPPING);
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
        
        assertEquals(1, mappingStatus.errors);
        assertEquals(1, unspecifiedMappingStatus.errors);
    }

    @Test
    void testProcessWithInvalidPayload() throws Exception {
        // Given
        mapping.mappingType = MappingType.JSON;
        // Provide invalid JSON to test error handling
        when(connectorMessage.getPayload()).thenReturn("invalid json".getBytes());
        
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When
        processor.process(exchange);

        // Then - Error handling should be called, but no setHeader
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        // Verify setHeader is NOT called when deserialization fails
        verify(message, never()).setHeader(eq("processingContext"), any(ProcessingContext.class));
        
        assertEquals(1, mappingStatus.errors);
    }

    @Test
    void testProcessWithNullConnectorMessage() throws Exception {
        // Given
        when(message.getHeader("connectorMessage", ConnectorMessage.class)).thenReturn(null);
        mapping.mappingType = MappingType.JSON;

        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When & Then
        assertThrows(Exception.class, () -> processor.process(exchange));
    }

    @Test
    void testProcessWithNullMapping() throws Exception {
        // Given
        when(message.getBody(Mapping.class)).thenReturn(null);

        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectMappingService(processor, mappingService);

        // When & Then
        assertThrows(NullPointerException.class, () -> processor.process(exchange));
    }

    @Test
    void testConstructorInitializesDeserializers() {
        // Given & When
        DeserializationInboundProcessor newProcessor = new DeserializationInboundProcessor();

        // Then
        assertNotNull(newProcessor);
    }

    @Test
    void testAllMappingTypesWithValidPayloads() throws Exception {
        MappingType[] mappingTypes = {
            MappingType.JSON, 
            MappingType.FLAT_FILE, 
            MappingType.HEX,
            MappingType.PROTOBUF_INTERNAL,
            MappingType.EXTENSION_SOURCE,
            MappingType.EXTENSION_SOURCE_TARGET,
            MappingType.CODE_BASED
        };

        for (MappingType type : mappingTypes) {
            // Create fresh processor for each test
            DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
            injectMappingService(processor, mappingService);
            
            // Reset message mock
            reset(message);
            when(exchange.getIn()).thenReturn(message);
            when(message.getBody(Mapping.class)).thenReturn(mapping);
            when(message.getHeader("tenant", String.class)).thenReturn(TEST_TENANT);
            when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
            when(message.getHeader("connectorMessage", ConnectorMessage.class)).thenReturn(connectorMessage);
            
            // Given
            mapping.mappingType = type;
            setupValidPayload(type);

            // When
            processor.process(exchange);
            
            // Then
            verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
        }
    }

    private void injectMappingService(DeserializationInboundProcessor processor, MappingService mappingService) throws Exception {
        Field field = DeserializationInboundProcessor.class.getDeclaredField("mappingService");
        field.setAccessible(true);
        field.set(processor, mappingService);
    }
}