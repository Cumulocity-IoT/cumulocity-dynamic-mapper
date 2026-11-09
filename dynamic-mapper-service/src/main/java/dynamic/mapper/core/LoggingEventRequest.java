/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.core;

import java.util.Map;

import org.joda.time.DateTime;

import dynamic.mapper.model.status.LoggingEventType;

/**
 * Asks {@link C8YAgent} to write a logging event to the platform. Beans that only need to report
 * something (for example {@link CacheManager}) publish this event instead of depending on
 * {@code C8YAgent}, which itself depends on them.
 */
public record LoggingEventRequest(String message, LoggingEventType type, DateTime eventTime, String tenant,
        Map<String, String> properties) {
}
