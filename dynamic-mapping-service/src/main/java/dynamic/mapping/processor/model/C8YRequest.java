/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import dynamic.mapping.model.API;
import org.springframework.web.bind.annotation.RequestMethod;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class C8YRequest {
    private int predecessor = -1;;
    private RequestMethod method;
    private String source;
    private String externalIdType;
    private String request;
    private String response;
    private API targetAPI;
    private Exception error;
    // this property documents if a C8Y request was already submitted and is created only for documentation/testing purpose.
    // this happens when a device is created implicitly with mapping.createNonExistingDevice == true
    // private boolean alreadySubmitted;
    public boolean hasError() {
        return error != null;
    }
}
