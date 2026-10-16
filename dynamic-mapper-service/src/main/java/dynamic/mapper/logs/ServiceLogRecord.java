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

package dynamic.mapper.logs;

import java.time.Instant;

/**
 * One log record of the service: the header line plus any continuation lines (stack trace, multi-line
 * payload) that belong to it.
 *
 * @param tenant the tenant field of the line, or {@code null} when the line belongs to no tenant ({@code -})
 * @param text   the complete record exactly as logged, continuation lines included, no trailing newline
 */
public record ServiceLogRecord(Instant time, String level, String thread, String tenant, String logger,
        String text) {

    public boolean isAttributed() {
        return tenant != null && !tenant.isBlank();
    }
}
