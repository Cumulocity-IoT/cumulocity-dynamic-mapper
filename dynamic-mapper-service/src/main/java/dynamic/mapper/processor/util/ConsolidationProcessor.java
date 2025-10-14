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

import org.springframework.stereotype.Component;

import dynamic.mapper.processor.inbound.processor.BaseProcessor;
import dynamic.mapper.processor.model.ProcessingContext;

import org.apache.camel.Exchange;

@Component
public class ConsolidationProcessor extends BaseProcessor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        
        // The ProcessingContext itself contains all the processed data
        // No need to extract a separate "processedData" - the context IS the result
        
        exchange.getIn().setBody(context); // For aggregation - pass the context itself
    }
}
