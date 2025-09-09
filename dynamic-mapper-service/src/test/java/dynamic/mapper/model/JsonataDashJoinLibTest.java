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

package dynamic.mapper.model;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;
import com.dashjoin.jsonata.json.Json;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.Test;

@Slf4j
public class JsonataDashJoinLibTest {
    String jsonString01 = """
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

    String jsonString02 = """
            {
            "version": "0",
            "id": "TID-987654-1234567890",
            "detail-type": "geolocation",
            "source": "myapp.orders",
            "account": "123451235123",
            "time": "2024-05-21T15:17:43Z",
            "region": "us-west-1",
            "detail": {
                "sensorAlternateId": "TID-987654-1234567890",
                "capabilityAlternateId": "geolocation",
                "measures": [
                {
                    "latitude": 47.5381031,
                    "longitude": 14.885459,
                    "elevation": 745.3,
                    "accuracy": 5.4,
                    "origin": "gps",
                    "gatewayidentifier": "TID-GWID-436521",
                    "_time": "2022-04-29T12:01:23Z"
                }
                ]
            }
            }
            """;

    @Test
    void testExtractArray() {
        String expString = "mea";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            // log.info("Result in test testExtractArray(): {}",
            // toPrettyJsonString(extractedContent));
            assertEquals(true, extractedContent instanceof Collection);
        } catch (Exception e) {
            log.error("Exception in test testExtractArray()", e);
        }
    }

    @Test
    void testExtractObject() {
        String expString = "mea[0]";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            // log.info("Result in test testExtractObject(): {} is type: {}",
            // toPrettyJsonString(extractedContent),
            // extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Map);
        } catch (Exception e) {
            log.error("Exception in test testExtractObject()", e);
        }
    }

    @Test
    void testExtractBoolean() {
        String expString = "mea[0].iniialized";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            // log.info("Result in test testExtractBoolean(): {} is type: {}",
            // toPrettyJsonString(extractedContent),
            // extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Boolean);
        } catch (Exception e) {
            log.error("Exception in test testExtractBoolean()", e);
        }
    }

    @Test
    void testExtractString() {
        String expString = "mea[0].psid";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            // log.info("Result in test testExtractString(): {} is type: {}",
            // toPrettyJsonString(extractedContent),
            // extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof String);
        } catch (Exception e) {
            log.error("Exception in test testExtractString()", e);
        }
    }

    @Test
    void testExtractNumber() {
        String expString = "mea[0].values[0].value";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            // log.info("Result in test testExtractNumber(): {} is type: {}",
            // toPrettyJsonString(extractedContent),
            // extractedContent.getClass().getName());
            assertEquals(true, extractedContent instanceof Number);
        } catch (Exception e) {
            log.error("Exception in test testExtractNumber()", e);
        }
    }

    @Test
    void testExtractFailure() {
        String expString = "mea[0].new";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString01);
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
            Object payloadJsonNode = Json.parseJson(jsonString01);
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
            Object payloadJsonNode = Json.parseJson(jsonString01);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testIsNull(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent == null ? "null" : extractedContent.getClass().getName());
            assertEquals(true, extractedContent == null);
        } catch (Exception e) {
            log.error("Exception in test testIsNull()", e);
        }
    }

    @Test
    void testSplit() {
        String expString = "$split(detail.sensorAlternateId, '-')[-1]";
        try {
            Object payloadJsonNode = Json.parseJson(jsonString02);
            var expression = jsonata(expString);
            Object extractedContent = expression.evaluate(payloadJsonNode);
            log.info("Result in test testSplit(): {} is type: {}", toPrettyJsonString(extractedContent),
                    extractedContent == null ? "null" : extractedContent.getClass().getName());
            assertEquals("1234567890", extractedContent);
        } catch (Exception e) {
            log.error("Exception in test testSplit()", e);
        }
    }

}