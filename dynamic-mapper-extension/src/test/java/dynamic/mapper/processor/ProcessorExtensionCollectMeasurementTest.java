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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCollectMeasurement;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.flow.JavaExtensionContextImpl;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.processor.runtime.ProcessingContext;

class ProcessorExtensionCollectMeasurementTest {

  private final ProcessorExtensionCollectMeasurement extension = new ProcessorExtensionCollectMeasurement();
  private final FlowStateStore store = new FlowStateStore();

  private JavaExtensionContextImpl context(FlowStateStore withStore, ProcessingContext<byte[]> processing) {
    Mapping mapping = new Mapping();
    mapping.setIdentifier("collect-measurement");
    return new JavaExtensionContextImpl(null, null, "test-tenant", false, mapping, processing, withStore,
        withStore == null ? null : store.loadState("test-tenant", "collect-measurement"));
  }

  private Message<byte[]> message(String clientId, String sensor, double value) {
    String json = String.format(
        "{\"clientId\":\"%s\",\"time\":\"2026-01-01T00:00:00Z\",\"sensor\":\"%s\",\"value\":%s}", clientId, sensor, value);
    return new Message<>(json.getBytes(StandardCharsets.UTF_8), "collect/" + clientId, clientId, Map.of());
  }

  @Test
  void theSecondMessageProducesOneMeasurementWithBothValues() {
    assertEquals(0, extension.onMessage(message("s1", "temperature", 22.5), context(store, null)).length,
        "the first message waits for its partner");

    CumulocityObject[] result = extension.onMessage(message("s1", "humidity", 55.0), context(store, null));

    assertEquals(1, result.length);
    Map<?, ?> payload = (Map<?, ?>) result[0].getPayload();
    assertEquals("c8y_TemperatureMeasurement", payload.get("type"));
    assertEquals(22.5, ((Map<?, ?>) ((Map<?, ?>) payload.get("c8y_Temperature")).get("T")).get("value"));
    assertEquals(55.0, ((Map<?, ?>) ((Map<?, ?>) payload.get("c8y_Humidity")).get("H")).get("value"));
  }

  @Test
  void messagesOfDifferentDevicesAreNotMixed() {
    extension.onMessage(message("s1", "temperature", 22.5), context(store, null));

    assertEquals(0, extension.onMessage(message("s2", "humidity", 55.0), context(store, null)).length);
    assertEquals(1, extension.onMessage(message("s2", "temperature", 20.0), context(store, null)).length);
  }

  @Test
  void anUnknownSensorIsIgnoredWithAWarning() {
    ProcessingContext<byte[]> processing = ProcessingContext.<byte[]>builder().build();

    assertEquals(0, extension.onMessage(message("s1", "pressure", 1.0), context(store, processing)).length);
    assertEquals(1, processing.getWarnings().size());
  }

  @Test
  void aTestRunCollectsNothingAndSaysSo() {
    ProcessingContext<byte[]> processing = ProcessingContext.<byte[]>builder().build();
    JavaExtensionContextImpl test = context(null, processing);

    assertEquals(0, extension.onMessage(message("s1", "temperature", 22.5), test).length);
    assertEquals(0, extension.onMessage(message("s1", "humidity", 55.0), test).length);
    assertTrue(processing.getWarnings().stream().anyMatch(w -> w.contains("test run")));
  }
}
