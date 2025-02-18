/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapping.model;

import lombok.Data;

@Data
public class EventBinary {
    /**
     * Name of the attachment. If it is not provided in the request, it will be set as the event ID.
     */
    private String name;

    /**
     * A URL linking to this resource.
     */
    private String self;

    /**
     * Unique identifier of the event.
     */
    private String source;

    /**
     * Media type of the attachment.
     */
    private String type;
}