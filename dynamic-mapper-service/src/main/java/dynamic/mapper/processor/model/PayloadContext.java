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

package dynamic.mapper.processor.model;

import dynamic.mapper.model.BinaryInfo;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable context holding payload data for message processing.
 *
 * Thread-safe by design - all fields are final and immutable after deserialization.
 * Can be safely shared across multiple threads and processing operations.
 *
 * This context separates payload concerns from other processing aspects,
 * making it clear which operations need payload data.
 *
 * @param <T> the type of the deserialized payload (e.g., JsonNode, byte[], String)
 */
@Value
@Builder(toBuilder = true)
public class PayloadContext<T> {
    /**
     * The deserialized payload in its typed form.
     * Type depends on the mapping configuration (JSON, Protobuf, Flat File, etc.)
     */
    T deserializedPayload;

    /**
     * The raw payload as received before deserialization.
     * Preserved for cases where original format is needed (e.g., debugging, logging)
     */
    Object rawPayload;

    /**
     * Binary attachment information for payloads with binary attachments.
     * Contains metadata about binary data (name, type, content) for events with attachments.
     */
    BinaryInfo binaryInfo;

    /**
     * Checks if this payload has binary attachment information.
     *
     * @return true if binaryInfo is present and not empty
     */
    public boolean hasBinaryInfo() {
        return binaryInfo != null && binaryInfo.getData() != null;
    }

    /**
     * Creates a copy of this context with different binary info.
     *
     * @param newBinaryInfo the new binary info
     * @return a new PayloadContext with the updated binary info
     */
    public PayloadContext<T> withBinaryInfo(BinaryInfo newBinaryInfo) {
        return this.toBuilder()
            .binaryInfo(newBinaryInfo)
            .build();
    }
}
