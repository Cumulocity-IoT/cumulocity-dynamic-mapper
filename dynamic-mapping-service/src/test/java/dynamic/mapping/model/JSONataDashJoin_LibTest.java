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

package dynamic.mapping.model;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;
import com.dashjoin.jsonata.json.Json;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.Test;

@Slf4j
public class JSONataDashJoin_LibTest {
    String jsonString = """
                {
                    "isNullField": null,
                    "mea": [
                        {
                        "tid": "uuid_01",
                        "iniialized": true,
                        "psid": "Crest",
                        "devicePath": "path01_80_X03_VVB001StatusB_Crest",
                        "values": [
                            {
                            "value": 64.6,
                            "timestamp": 1734299911000
                            }
                        ]
                        },
                        {
                        "tid": "uuid_02",
                        "iniialized": false,
                        "psid": "Crest",
                        "devicePath": "path01_80_X03_VVB001StatusB_Crest",
                        "values": [
                            {
                            "value": 15.6,
                            "timestamp": 1734299921347
                            }
                        ]
                        }
                    ]
            }
            """;

    @Test
    void testExtractArray() {
        String expString = "mea";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractArray(): {}", toPrettyJsonString(extractedContent));
            assertEquals(true, extractedContent instanceof Collection);
        } catch (Exception e) {
            log.error("Exception in test testExtractArray()", e);
        }
    }

    @Test
    void testExtractObject() {
        String expString = "mea[0]";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractObject(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Map);
        } catch (Exception e) {
            log.error("Exception in test testExtractObject()", e);
        }
    }

    @Test
    void testExtractBoolean() {
        String expString = "mea[0].iniialized";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractBoolean(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Boolean);
        } catch (Exception e) {
            log.error("Exception in test testExtractBoolean()", e);
        }
    }

    @Test
    void testExtractString() {
        String expString = "mea[0].psid";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractString(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof String);
        } catch (Exception e) {
            log.error("Exception in test testExtractString()", e);
        }
    }

    @Test
    void testExtractNumber() {
        String expString = "mea[0].values[0].value";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractNumber(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Number);
        } catch (Exception e) {
            log.error("Exception in test testExtractNumber()", e);
        }
    }

    @Test
    void testExtractFailure() {
        String expString = "mea[0].new";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testExtractFailure(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent == null ? "null" : extractedContent.getClass().getName());
            assertEquals(true, extractedContent == null);
        } catch (Exception e) {
            log.error("Exception in test testExtractFailure()", e);
        }
    }


    @Test
    void testNonExisting() {
        String expString = "notExisting";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testNonExisting(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent == null ? "null" : extractedContent.getClass().getName());
            assertEquals(true, extractedContent == null);
        } catch (Exception e) {
            log.error("Exception in test testNonExisting()", e);
        }
    }

    @Test
    void testIsNull() {
        String expString = "isNullField";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testIsNull(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent == null ? "null" : extractedContent.getClass().getName());
            assertEquals(true, extractedContent == null);
        } catch (Exception e) {
            log.error("Exception in test testIsNull()", e);
        }
    }


}