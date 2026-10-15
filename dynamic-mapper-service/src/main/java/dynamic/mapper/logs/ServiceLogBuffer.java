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

import java.util.List;
import java.util.Map;

/** Log records captured inside the process, waiting to be stored, per tenant. */
public interface ServiceLogBuffer {

    /** Takes everything captured so far, per tenant, oldest first, and empties the buffer. */
    Map<String, List<ServiceLogRecord>> drainAll();

    /** Records lost to the per-tenant bound since the last call, per tenant; resets the counts. */
    Map<String, Integer> takeDropped();

    /** Puts records back at the front after a failed store, so the next run retries them in order. */
    void restore(String tenant, List<ServiceLogRecord> records);
}
