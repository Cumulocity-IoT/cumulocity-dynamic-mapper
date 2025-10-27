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

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ProcessingContextAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        ProcessingContext<?> newContext = newExchange.getIn().getBody(ProcessingContext.class);

        if (oldExchange == null) {
            // First result
            List<ProcessingContext<Object>> contexts = new ArrayList<>();
            contexts.add((ProcessingContext<Object>) newContext);
            newExchange.getIn().setHeader("processedContexts", contexts);
            return newExchange;
        }

        // Aggregate contexts
        @SuppressWarnings("unchecked")
        List<ProcessingContext<Object>> existingContexts = oldExchange.getIn().getHeader("processedContexts",
                List.class);
        existingContexts.add((ProcessingContext<Object>) newContext);

        oldExchange.getIn().setHeader("processedContexts", existingContexts);

        // Clean up the new exchange's context after aggregation
        if (newContext != null) {
            try {
                newContext.close();
            } catch (Exception e) {
                log.warn("{} - Error closing context during aggregation: {}", newContext.getTenant(), e.getMessage());
            }
        }
        return oldExchange;
    }
}
