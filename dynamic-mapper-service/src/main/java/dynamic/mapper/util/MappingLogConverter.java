/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback conversion word {@code %mappingTag}: {@code [m:<identifier>] } (with the trailing space) for a line written
 * while processing a mapping, and nothing otherwise, so lines outside a mapping keep their format. Registered by
 * {@link TenantLogging}.
 *
 * <p>The stored service log is filtered on exactly this text, at its fixed header position (see
 * {@code MappingLogRecords}); keep the two in step.
 */
public class MappingLogConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String mapping = MappingLogContext.mappingOf(event.getMDCPropertyMap());
        return mapping == null ? "" : "[m:" + mapping + "] ";
    }
}
