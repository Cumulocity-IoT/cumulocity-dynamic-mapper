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

package dynamic.mapper.processor.util;

/**
 * Canonical names of the Apache Camel exchange headers exchanged between the
 * inbound and outbound processing routes and their processors.
 *
 * <p>Processors communicate almost entirely through these headers on the Camel
 * {@code Message}. Referencing them through these constants instead of repeating
 * string literals means a renamed or mistyped handoff fails at compile time
 * rather than as a {@code null} header surfacing as a {@link NullPointerException}
 * deep inside an unrelated processor.
 *
 * <p>The string values are part of the route contract and must not be changed
 * without updating every producer and consumer in lock-step.
 */
public final class CamelHeaders {

    private CamelHeaders() {
        // constants holder
    }

    /** The per-message {@code ProcessingContext} carried through a route. */
    public static final String PROCESSING_CONTEXT = "processingContext";

    /** Accumulated list of {@code ProcessingContext}s built by the split aggregator. */
    public static final String PROCESSED_CONTEXTS = "processedContexts";

    /** The list of {@code Mapping}s to evaluate for the current message. */
    public static final String MAPPINGS = "mappings";

    /** Identifier of the connector that delivered / will receive the message. */
    public static final String CONNECTOR_IDENTIFIER = "connectorIdentifier";

    /** The tenant the message is being processed for. */
    public static final String TENANT = "tenant";

    /** The {@code ProcessingResultWrapper} enabling in-flight cancellation checks. */
    public static final String PROCESSING_RESULT_WRAPPER = "processingResultWrapper";

    /** Flag marking a test (non-publishing) invocation. */
    public static final String TESTING = "testing";

    /** The active {@code ServiceConfiguration}. */
    public static final String SERVICE_CONFIGURATION = "serviceConfiguration";

    /** The final {@code ProcessingResult} produced by consolidation. */
    public static final String PROCESSING_RESULT = "processingResult";

    /** Raw payload as a String. */
    public static final String PAYLOAD_STRING = "payloadString";

    /** Raw payload as a byte array. */
    public static final String PAYLOAD_BYTES = "payloadBytes";

    /** Flag enabling parallel per-request processing within a single mapping. */
    public static final String PARALLEL_PROCESSING = "parallelProcessing";

    /** The inbound {@code ConnectorMessage} delivered by the broker. */
    public static final String CONNECTOR_MESSAGE = "connectorMessage";

    /** The outbound {@code C8YMessage} originating from Cumulocity. */
    public static final String C8Y_MESSAGE = "c8yMessage";

    /** Source (managed object) id, set on outbound dispatch. */
    public static final String SOURCE = "source";

    /** Client id, set on inbound dispatch. */
    public static final String CLIENT = "client";
}
