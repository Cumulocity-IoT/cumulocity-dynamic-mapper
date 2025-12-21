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

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RequestAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // For parallel processing of DynamicMapperRequests:
        // - Each split request is processed by SendInboundProcessor
        // - The processor updates the request object in-place (setting response/error)
        // - All requests share the same ProcessingContext via the "processingContext" header
        // - We need to preserve the processingContext header across all aggregations

        if (oldExchange == null) {
            // First request processed - return it with the processingContext header
            return newExchange;
        }

        // Subsequent requests: the processingContext in the header contains ALL requests
        // (both already processed and currently processing), because they all reference
        // the same ProcessingContext object. We just need to preserve this header.
        // Always return oldExchange to maintain the original exchange with all headers intact.
        return oldExchange;
    }
}