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
package dynamic.mapper.processor.flow;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Details of external ID which will be looked up by IDP to get the C8Y id.
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ExternalId {
    
    /**
     * External id to be looked up and/or created to get C8Y "id"
     */
    private String externalId;
    
    /**
     * External id type e.g. "c8y_Serial"
     */
    private String type;
}