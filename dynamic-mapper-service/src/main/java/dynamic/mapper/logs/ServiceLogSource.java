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

import java.io.IOException;
import java.time.Instant;

/** Where the native log of this service instance is read from. */
public interface ServiceLogSource {

    /** One window of the log as the platform returns it. */
    record Window(Instant dateFrom, Instant dateTo, String logs, boolean truncated) {
    }

    /** Name of the instance whose log is read; used to key the events. */
    String instance();

    /** The log from {@code from} on, in the platform's window semantics ({@code truncated} = more follows). */
    Window fetch(Instant from) throws IOException, InterruptedException;
}
