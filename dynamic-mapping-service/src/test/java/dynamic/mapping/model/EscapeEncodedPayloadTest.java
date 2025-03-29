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

package dynamic.mapping.model;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class EscapeEncodedPayloadTest {
    private ObjectMapper objectMapper;
    private Mapping mapping;
    private static final String TEST_MAPPING_FILE = "/mapping-escaped-payload.txt";

    @Nested
    @DisplayName("Deserialization Tests")
    class DeserializationTests {
        @Test
        @DisplayName("Should successfully deserialize mapping with specific escaped strings")
        void testDeserializeMapping() {
            objectMapper = new ObjectMapper();
            try {
                try (InputStream inputStream = getClass().getResourceAsStream(TEST_MAPPING_FILE)) {
                    if (inputStream == null) {
                        throw new IOException("Could not find file: " + TEST_MAPPING_FILE);
                    }

                    // Convert InputStream to String
                    String textFromInputStream = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

                    // Process the text with your replacement logic
                    String processedText = textFromInputStream
                            .replace("\"[", "[")          // 1. replace "[ with [
                            .replace("]\"", "]")          // 2. replace ]" with ]
                            .replace("\\\\\"", "___\"")   // 3. replace \\\" with ___"
                            .replace("\\\"", "\"")        // 4. replace \" with "
                            .replace("___\"", "\"");      // 5. replace ___" with \"

                    // Create a new InputStream from the processed text
                    InputStream processedInputStream = new ByteArrayInputStream(
                            processedText.getBytes(StandardCharsets.UTF_8));

                    // Use the processed InputStream for deserialization
                    mapping = objectMapper.readValue(
                            processedInputStream,
                            new TypeReference<Mapping>() {
                            });
                } catch (IOException e) {
                    log.error("Error deserializing mappings file: {}", e.getMessage());
                    throw e;
                }
            } catch (IOException e) {
                fail("Failed to load test data: " + e.getMessage());
            }
            assertNotNull(mapping, "Mappings should not be null");

            // Add additional assertions to verify the content was properly processed
            // For example:
            // assertEquals("expected value", mapping.getSomeProperty());
        }

        @Test
        @DisplayName("Should correctly process escaped characters")
        void testEscapedCharacterReplacement() {
            String input = "\\\\\"test\\\" data with \"[array]\" elements";
            String expected = "\"test\" data with [array] elements";

            String actual = input
                    .replace("\\\\\"", "___\"") // 1. replace \\\" with ___"
                    .replace("\\\"", "\"") // 2. replace \" with "
                    .replace("___\"", "\"") // 3. replace ___" with \"
                    .replace("\"[", "[") // 4. replace "[ with [
                    .replace("]\"", "]"); // 5. replace ]" with ]

            assertEquals(expected, actual, "String replacement should work as expected");
        }
    }
}