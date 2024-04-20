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

import com.api.jsonata4java.expressions.Expressions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.LongNode;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

@Slf4j
public class JSONataTest {
    ObjectMapper mapper = new ObjectMapper();

    // test is disabled because of a current limitation in the JSONata library: https://github.com/IBM/JSONata4Java/issues/305
    // @Test
    // void testNumberFunctionForHex() {

    //     String exp = "$number(value)";
    //     String jsonString = "{\"value\":\"0x575\"}";
    //     try {
    //         JsonNode payloadJsonNode = mapper.readTree(jsonString);
    //         Expressions expr = Expressions.parse(exp);
    //         JsonNode extractedContent = expr.evaluate(payloadJsonNode);
    //         log.info("Result in test testNumberFunctionForHex(): {}", extractedContent.toPrettyString());
    //         assertEquals(1397, extractedContent);
    //     } catch (Exception e) {
    //         log.error("Exception in test testNumberFunctionForHex()", e);
    //     }
    // }

    @Test
    void testNumberFunctionForDecimal() {

        try {
            // String exp = "$number(value)";
            String exp = "$number('575')";
            String jsonString = "{\"value\":\"575\"}";
            JsonNode payloadJsonNode = mapper.readTree(jsonString);
            Expressions expr = Expressions.parse(exp);
            JsonNode extractedContent = expr.evaluate(payloadJsonNode);
            LongNode extractedContentLong = null;
            if (extractedContent instanceof LongNode) {
                extractedContentLong = (LongNode) extractedContent;
            } else {
                fail();
            }
            log.info("Result in test testNumberFunctionForDecimal(): {}", extractedContentLong.asInt());
            assertEquals(575, extractedContentLong.asInt());
        } catch (Exception e) {
            log.error("Exception in test testNumberFunctionForDecimal()", e);
        }
    }
}