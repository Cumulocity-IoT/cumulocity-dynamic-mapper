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

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.extension.external.outbound.ProcessorExtensionBatchMeasurements;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.flow.JavaExtensionContextImpl;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.processor.runtime.ProcessingContext;

class ProcessorExtensionBatchMeasurementsTest {

  private final ProcessorExtensionBatchMeasurements extension = new ProcessorExtensionBatchMeasurements();
  private final FlowStateStore store = new FlowStateStore();

  private JavaExtensionContextImpl context(FlowStateStore withStore, ProcessingContext<Object> processing) {
    Mapping mapping = new Mapping();
    mapping.setIdentifier("batch-measurements");
    mapping.setTargetAPI(API.MEASUREMENT);
    return new JavaExtensionContextImpl(null, null, "test-tenant", false, mapping, processing, withStore,
        withStore == null ? null : store.loadState("test-tenant", "batch-measurements"));
  }

  private Message<Object> measurement(String sourceId, String time, double value) {
    Map<String, Object> payload = Map.of(
        "source", Map.of("id", sourceId),
        "time", time,
        "c8y_TemperatureMeasurement", Map.of("T", Map.of("value", value, "unit", "C")));
    return new Message<>(payload, "measurements/" + sourceId, "test-client", Map.of());
  }

  @Test
  void theThirdMeasurementPublishesTheWholeBatchOrderedByTime() throws Exception {
    // delivered out of order on purpose
    assertEquals(0, extension.onMessage(measurement("1", "2026-01-01T00:02:00Z", 3.0), context(store, null)).length);
    assertEquals(0, extension.onMessage(measurement("1", "2026-01-01T00:00:00Z", 1.0), context(store, null)).length);

    DeviceMessage[] result = extension.onMessage(measurement("1", "2026-01-01T00:01:00Z", 2.0), context(store, null));

    assertEquals(1, result.length);
    assertEquals("measurements/batch/1", result[0].getTopic());
    // compare as JSON: the key order inside a reading is not defined
    assertEquals(new ObjectMapper().readTree("[{\"time\":\"2026-01-01T00:00:00Z\",\"unit\":\"C\",\"value\":1.0},"
        + "{\"time\":\"2026-01-01T00:01:00Z\",\"unit\":\"C\",\"value\":2.0},"
        + "{\"time\":\"2026-01-01T00:02:00Z\",\"unit\":\"C\",\"value\":3.0}]"),
        new ObjectMapper().readTree((String) result[0].getPayload()));
  }

  @Test
  void eachDeviceFillsItsOwnBatch() throws ProcessingException {
    extension.onMessage(measurement("1", "2026-01-01T00:00:00Z", 1.0), context(store, null));
    extension.onMessage(measurement("2", "2026-01-01T00:00:00Z", 1.0), context(store, null));
    extension.onMessage(measurement("1", "2026-01-01T00:01:00Z", 2.0), context(store, null));

    assertEquals(0, extension.onMessage(measurement("2", "2026-01-01T00:01:00Z", 2.0), context(store, null)).length);
    assertEquals(1, extension.onMessage(measurement("1", "2026-01-01T00:02:00Z", 3.0), context(store, null)).length);
  }

  @Test
  void aRedeliveredMeasurementIsNotCountedTwice() throws ProcessingException {
    extension.onMessage(measurement("1", "2026-01-01T00:00:00Z", 1.0), context(store, null));
    extension.onMessage(measurement("1", "2026-01-01T00:00:00Z", 1.0), context(store, null));

    assertEquals(0, extension.onMessage(measurement("1", "2026-01-01T00:01:00Z", 2.0), context(store, null)).length,
        "two different times so far, the batch is not full");
  }

  @Test
  void aTestRunPublishesNothingAndSaysSo() throws ProcessingException {
    ProcessingContext<Object> processing = ProcessingContext.<Object>builder().build();

    assertEquals(0, extension.onMessage(measurement("1", "2026-01-01T00:00:00Z", 1.0), context(null, processing)).length);
    assertTrue(processing.getWarnings().stream().anyMatch(w -> w.contains("test run")));
  }
}
