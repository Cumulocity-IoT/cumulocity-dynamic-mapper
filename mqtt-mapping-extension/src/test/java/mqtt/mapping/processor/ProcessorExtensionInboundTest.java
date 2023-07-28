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

package mqtt.mapping.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.processor.extension.external.CustomEventOuter;
import mqtt.mapping.processor.extension.external.ProcessorExtensionInboundCustomEvent;
import mqtt.mapping.processor.extension.external.CustomEventOuter.CustomEvent;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;

@Slf4j
public class ProcessorExtensionInboundTest {

  @Test
  void testDeserializeCustomEvent() {

    CustomEventOuter.CustomEvent proto = CustomEvent.newBuilder()
    .setExternalIdType("c8y_Serial")
    .setExternalId("berlin_01")
    .setTxt("Dummy Text")
    .setEventType("type_Dummy")
    .build();

    ProcessorExtensionInboundCustomEvent extension = new ProcessorExtensionInboundCustomEvent();
    ProcessingContext context = new ProcessingContext();
    context.setPayload(proto.toByteArray());
    Mapping m1 = new Mapping();
    m1.setTargetAPI(API.EVENT);
    context.setMapping(m1);

    extension.extractFromSource(context);

    ArrayList<SubstituteValue> extractedTypes = (ArrayList) context.getPostProcessingCache().get("type");
    assertEquals( extractedTypes.size(), 1);
    SubstituteValue extractedType = extractedTypes.get(0);
    log.info("Extracted: {}", extractedType.typedValue());
    assertEquals( (String)extractedType.typedValue(), "type_Dummy");
   // assertEquals( (JsonNode)extractedType.typedValue(), "Dummy Text");

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

  //   log.info("My configuration is active: {}", ConfigurationConnection.isEnabled(conf));
  //   assertEquals(false, ConfigurationConnection.isEnabled(conf));
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
