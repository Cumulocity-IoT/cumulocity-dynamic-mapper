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
package dynamic.mapper.processor.outbound.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeserializationOutboundProcessor extends BaseProcessor {

    @Override
    public void process(Exchange exchange) throws Exception {
        C8YMessage c8yMessage = exchange.getIn().getHeader(CamelHeaders.C8Y_MESSAGE, C8YMessage.class);

        String tenant = c8yMessage.getTenant();
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        Boolean testing = exchange.getIn().getHeader(CamelHeaders.TESTING, Boolean.class);

        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader(CamelHeaders.SERVICE_CONFIGURATION,
                ServiceConfiguration.class);

        ProcessingContext<Object> context = createProcessingContextAsObject(tenant, mapping, c8yMessage,
                serviceConfiguration, testing);

        exchange.getIn().setHeader(CamelHeaders.PROCESSING_CONTEXT, context);

    }

}
