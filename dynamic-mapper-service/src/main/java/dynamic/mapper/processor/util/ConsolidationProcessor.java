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

import dynamic.mapper.processor.CommonProcessor;
import dynamic.mapper.processor.model.ProcessingContext;

import org.apache.camel.Exchange;

/**
 * Sets the exchange body to the current ProcessingContext so that Camel's
 * aggregation strategy ({@link ProcessingContextAggregationStrategy}) can read
 * it after every split leg. Called before each {@code .stop()} and {@code .end()}
 * in the inbound and outbound route pipelines.
 *
 * <p>The name "ConsolidationProcessor" is historical. Its only responsibility is
 * moving the context from the in-header to the exchange body.
 */
@Component
public class ConsolidationProcessor extends CommonProcessor {

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);
        exchange.getIn().setBody(context);
    }
}
