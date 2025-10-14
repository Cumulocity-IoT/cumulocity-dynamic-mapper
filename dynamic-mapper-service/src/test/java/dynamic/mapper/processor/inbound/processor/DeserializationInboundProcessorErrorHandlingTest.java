/*
 * Copyright (c) 2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.inbound.deserializer.PayloadDeserializer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
class DeserializationInboundProcessorErrorHandlingTest {

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

    @Mock
    private MappingStatus mappingStatus;

    @Mock
    private MappingStatus unspecifiedMappingStatus;

    @Mock
    private PayloadDeserializer<Object> mockDeserializer;

    @InjectMocks
    private DeserializationInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping; // Real object, not mock

    @BeforeEach
    void setUp() {
        // Create real Mapping object
        mapping = Mapping.builder().build();
        
        when(exchange.getIn()).thenReturn(message);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("tenant", String.class)).thenReturn(TEST_TENANT);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
        when(message.getHeader("connectorMessage", ConnectorMessage.class)).thenReturn(connectorMessage);

    }

    @Test
    void testDeserializationIOExceptionHandling() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        
        // Use reflection to replace the deserializer with a mock that throws IOException
        replaceDeserializerWithMock(MappingType.JSON);
        when(mockDeserializer.deserializePayload(eq(mapping), eq(connectorMessage)))
                .thenThrow(new IOException("Test IO Exception"));

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        verify(mockDeserializer).deserializePayload(eq(mapping), eq(connectorMessage));
        assertEquals(1, (int) mappingStatus.errors);
    }

    @Test
    void testByteArrayDeserializationIOExceptionHandling() throws Exception {
        // Given
        mapping.setMappingType(MappingType.PROTOBUF_INTERNAL);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        
        // Use reflection to replace the deserializer with a mock that throws IOException
        replaceDeserializerWithMock(MappingType.PROTOBUF_INTERNAL);
        when(mockDeserializer.deserializePayload(eq(mapping), eq(connectorMessage)))
                .thenThrow(new IOException("Test IO Exception"));

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        verify(mockDeserializer).deserializePayload(eq(mapping), eq(connectorMessage));
        assertEquals(1, mappingStatus.errors);
    }

    @SuppressWarnings("unchecked")
    private void replaceDeserializerWithMock(MappingType mappingType) throws Exception {
        Field deserializersField = DeserializationInboundProcessor.class.getDeclaredField("deserializers");
        deserializersField.setAccessible(true);
        Map<MappingType, PayloadDeserializer<?>> deserializers = 
            (Map<MappingType, PayloadDeserializer<?>>) deserializersField.get(processor);
        deserializers.put(mappingType, mockDeserializer);
    }
}