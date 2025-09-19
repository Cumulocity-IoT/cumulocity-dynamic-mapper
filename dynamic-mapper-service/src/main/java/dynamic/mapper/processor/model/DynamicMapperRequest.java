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

package dynamic.mapper.processor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import dynamic.mapper.model.API;
import org.springframework.web.bind.annotation.RequestMethod;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DynamicMapperRequest {
    @Builder.Default
    private int predecessor = -1;;
    private RequestMethod method;
    private API api;
    private String sourceId;
    private String externalId;
    private String externalIdType;
    private String request;
    private String response;
    private Exception error;
    // this property documents if a C8Y request was already submitted and is created only for documentation/testing purpose.
    // this happens when a device is created implicitly with mapping.createNonExistingDevice == true
    // private boolean alreadySubmitted;
    public boolean hasError() {
        return error != null;
    }
}
