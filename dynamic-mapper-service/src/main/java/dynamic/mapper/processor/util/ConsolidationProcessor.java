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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.mapping.MappingService;

import dynamic.mapper.processor.CommonProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * Called before each terminal return in the inbound and outbound router pipelines — i.e. on
 * every terminal path of a single mapping's processing, whether or not it reached JS execution.
 *
 * <p>Also closes the ProcessingContext's GraalVM resources here (idempotent —
 * {@link ProcessingContext#close()} is a no-op if nothing was ever set up). This
 * is the one point every leg passes through, so it catches the cases where a
 * SMART_FUNCTION mapping's GraalVM Context was created during enrichment but the
 * route never reached {@code AbstractFlowProcessor} (e.g. filtered out by
 * {@code FilterInboundProcessor} right after enrichment) — otherwise that Context
 * (and its Engine reference) would leak.
 *
 * <p>Because it is the single terminal point of every leg, it is also where a mapping's
 * consecutive-failure streak is cleared: a leg that ends without an error means the mapping
 * is working again, so the {@code maxFailureCount} counter must start over. See
 * {@code docs/feature/reliability.md}.
 *
 * <p>The name "ConsolidationProcessor" is historical, predating the removal of Camel from
 * this pipeline.
 */
@Component
public class ConsolidationProcessor extends CommonProcessor {

    @Autowired
    private MappingService mappingService;

    public void process(ProcessingContext<?> context) throws Exception {
        if (context != null) {
            // A clean leg ends the failure streak. Skipped for test runs, which must never
            // change the runtime status of a mapping.
            if (!context.hasError() && !context.isTesting() && context.getMapping() != null) {
                mappingService.resetFailureCountOnSuccess(context.getTenant(), context.getMapping());
            }
            context.close();
        }
    }
}
