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

package dynamic.mapper.model;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing notification subscription details and status")
public class NotificationSubscriptionResponse {

    @Schema(description = "Cumulocity IoT API type", example = "MEASUREMENT")
    private API api;

    @Schema(description = "Name of the subscription", example = "temperature-sensors")
    private String subscriptionName;

    @Schema(description = "List of subscribed devices")
    private List<Device> devices;

    @Schema(description = "List of subscribed device types")
    private List<String> types;

    @Schema(description = "Unique subscription identifier")
    private String subscriptionId;

    @Schema(description = "Current subscription status")
    private SubscriptionStatus status;

    public enum SubscriptionStatus {
        ACTIVE, INACTIVE, ERROR, PENDING
    }
}