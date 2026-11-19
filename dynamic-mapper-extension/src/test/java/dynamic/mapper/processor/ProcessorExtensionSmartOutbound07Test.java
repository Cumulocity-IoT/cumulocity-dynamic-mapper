/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.extension.external.outbound.ProcessorExtensionSmartOutbound07;
import dynamic.mapper.processor.flow.JavaExtensionContextImpl;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.Message;

class ProcessorExtensionSmartOutbound07Test {

  private final ProcessorExtensionSmartOutbound07 extension = new ProcessorExtensionSmartOutbound07();

  private JavaExtensionContextImpl context(String externalId) {
    Mapping mapping = new Mapping();
    mapping.setTargetAPI(API.OPERATION);
    JavaExtensionContextImpl context = new JavaExtensionContextImpl(null, null, "test-tenant", false, mapping, null);
    context.setExternalId(externalId);
    return context;
  }

  private Message<Object> operation(Object payload) {
    return new Message<>(payload, "command/berlin_01", "test-client", Map.of());
  }

  @Test
  void sendsTheCommandAsTextToTheTopicOfTheExternalId() throws ProcessingException {
    Map<String, Object> payload = Map.of("deviceId", "12345", "c8y_Command", Map.of("text", "GTRTO=gv300,3,,,,,,0001$"));

    DeviceMessage[] result = extension.onMessage(operation(payload), context("berlin_01"));

    assertEquals(1, result.length);
    assertEquals("command/berlin_01", result[0].getTopic());
    assertEquals("AT+GTRTO=gv300,3,,,,,,0001$", result[0].getPayload(), "A string, so it is sent as text");
    assertEquals(DeviceMessage.ENCODING_TEXT, result[0].getPayloadEncoding());
    assertEquals("text/plain", result[0].getContentType());
  }

  @Test
  void publishesNothingWithoutACommandText() throws ProcessingException {
    JavaExtensionContextImpl context = context("berlin_01");

    assertEquals(0, extension.onMessage(operation(Map.of("deviceId", "12345")), context).length);
    assertEquals(0, extension.onMessage(operation(Map.of("c8y_Command", Map.of("text", ""))), context).length);
  }

  @Test
  void publishesNothingWithoutAnExternalId() throws ProcessingException {
    Map<String, Object> payload = Map.of("c8y_Command", Map.of("text", "GTRTO=gv300,3,,,,,,0001$"));

    assertEquals(0, extension.onMessage(operation(payload), context(null)).length);
  }
}
