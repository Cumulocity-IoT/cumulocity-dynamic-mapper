/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.CumulocityType;
import dynamic.mapper.processor.flow.SimpleDataPreparationContext;
import dynamic.mapper.processor.flow.Message;

import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.processor.extension.external.inbound.CustomEventOuter;
import dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomEvent;
import dynamic.mapper.processor.extension.external.inbound.CustomEventOuter.CustomEvent;
import dynamic.mapper.model.API;

@Slf4j
public class ProcessorExtensionTest {

  @Test
  void testDeserializeCustomEvent() {

    CustomEventOuter.CustomEvent proto = CustomEvent.newBuilder()
    .setExternalIdType("c8y_Serial")
    .setExternalId("berlin_01")
    .setTxt("Dummy Text")
    .setEventType("type_Dummy")
    .build();

    ProcessorExtensionCustomEvent extension = new ProcessorExtensionCustomEvent();
    byte[] payload = proto.toByteArray();

    Mapping mapping = new Mapping();
    mapping.setTargetAPI(API.EVENT);
    mapping.setExternalIdType("c8y_Serial");

    // Create Message wrapper
    Message<byte[]> message = new Message<>(payload, "test/topic", "test-client", java.util.Map.of());

    // Use SimpleDataPreparationContext with minimal setup
    SimpleDataPreparationContext context = new SimpleDataPreparationContext(
        null,  // flowContext
        null,  // c8yAgent
        "test-tenant",
        false,  // testing
        mapping,
        null   // processingContext
    );

    // Call the new pattern method
    CumulocityObject[] results = extension.onMessage(message, context);

    // Verify results
    assertNotNull(results);
    assertEquals(1, results.length);

    CumulocityObject event = results[0];
    assertEquals(CumulocityType.EVENT, event.getCumulocityType());
    assertNotNull(event.getPayload());

    log.info("Successfully processed event with type: {}", event.getCumulocityType());
  }


  // @Test
  // void testSplitTopic() {

  //   String t1 = "/d1/e1/f1/";
  //   String[] r1 = Mapping.splitTopicExcludingSeparatorAsArray(t1);
  //   log.info("My topicSplit: {}", Arrays.toString(r1));
  //   assertArrayEquals(new String[] {"d1", "e1", "f1"}, r1);


  //   String t2 = "///d1/e1/f1///";
  //   String[] r2 = Mapping.splitTopicExcludingSeparatorAsArray(t2);
  //   log.info("My topicSplit: {}, size: {}", Arrays.toString(r2), r2.length);
  //   assertArrayEquals(new String[] {"d1", "e1", "f1"}, r2);


  //   String t3 = "///d1/e1/f1///";
  //   String[] r3 = Mapping.splitTopicIncludingSeparatorAsArray(t3);
  //   log.info("My topicSplit: {}", Arrays.toString(r3));

  //   assertArrayEquals(new String[] {"/","d1", "/", "e1", "/","f1", "/"}, r3);

  // }


  // @Test
  // void testMQTTConfigurationIsEnabled() {
  //   ConfigurationConnection conf = null;

  //   log.info("My configuration is active: {}", ConfigurationConnection.getEnabled(conf));
  //   assertEquals(false, ConfigurationConnection.getEnabled(conf));
  // }

  // @Test
  // void testNeedsRepair() {

  //   ProcessingContext p1 = new ProcessingContext();
  //   p1.addCardinality("value1",   5);
  //   p1.addCardinality("value2",   5);
  //   p1.addCardinality(ProcessingContext.SOURCE_ID, 1);
  //   // log.info("My neeRepair1: {}", p1.needsRepair);
  //   assertEquals(false, p1.isNeedsRepair());


  //   ProcessingContext p2 = new ProcessingContext();
  //   p2.addCardinality("value1",   5);
  //   p2.addCardinality("value2",   4);
  //   p2.addCardinality(ProcessingContext.SOURCE_ID, 1);
  //   // log.info("My neeRepair1: {}", p2.needsRepair);
  //   assertEquals(true, p2.isNeedsRepair());

  // }

}
