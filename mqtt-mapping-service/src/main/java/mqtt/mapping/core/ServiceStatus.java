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

package mqtt.mapping.core;

import lombok.Data;

@Data
// @NoArgsConstructor
// @AllArgsConstructor
public class ServiceStatus {
    private Status status;

    public ServiceStatus(Status status){
        this.status = status;
    }

    public ServiceStatus(){
        this.status = Status.NOT_READY;
    }
    
    public static ServiceStatus connected() {
        return new ServiceStatus(Status.CONNECTED);
    }
    
    public static ServiceStatus activated() {
        return new ServiceStatus(Status.ENABLED);
    }
    
    public static ServiceStatus configured() {
        return new ServiceStatus(Status.CONFIGURED);
    }

    public static ServiceStatus notReady() {
        return new ServiceStatus(Status.NOT_READY);
    }
}
