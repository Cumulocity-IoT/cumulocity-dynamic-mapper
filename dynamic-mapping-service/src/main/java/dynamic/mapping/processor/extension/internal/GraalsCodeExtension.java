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

package dynamic.mapping.processor.extension.internal;

import com.dashjoin.jsonata.json.Json;

import java.util.Map;

import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;

import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import jakarta.ws.rs.ProcessingException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraalsCodeExtension implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        try {
            Map jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));

            final Value mapFunc = context.getExtractFromSourceFunc();
            final Value result = mapFunc.execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),jsonObject));
            final SubstitutionResult typedResult = result.as(new TypeLiteral<>() {
            });

            log.info("Tenant {} - Result from javascript substitution: {}", context.getTenant(),
                    typedResult);

            for (Substitution item : typedResult.substitutions) {
                context.addToProcessingCache(item.key, item.value, TYPE.valueOf(item.type), RepairStrategy.valueOf(item.repairStrategy));
            }
            log.info("Tenant {} - New payload over GraalsCodeExtension: {}, {}", context.getTenant(),
                    jsonObject);
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }
}