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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request payload for creating or updating notification subscriptions")
public class NotificationSubscriptionRequest {

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Cumulocity IoT API type to subscribe to for notifications",
        implementation = API.class,
        example = "MEASUREMENT"
    )
    @NotNull(message = "API type is required")
    private API api;

    @Schema(
        description = "Optional name for the subscription to help identify it",
        example = "temperature-sensors"
    )
    @Size(max = 100, message = "Subscription name must not exceed 100 characters")
    private String subscriptionName;

    @Schema(
        description = "List of devices to include in the subscription. Child devices will be automatically discovered and included.",
        example = """
        [
          {
            "id": "12345",
            "name": "Temperature Sensor 01"
          }
        ]
        """
    )
    @Valid
    @Size(max = 1000, message = "Cannot subscribe to more than 1000 devices at once")
    private List<Device> devices;

    @Schema(
        description = "List of device types to monitor dynamically",
        example = """
        [
          "type_temperature-sensor",
          "type_humidity-sensor"  
        ]
        """
    )
    @Size(max = 50, message = "Cannot subscribe to more than 50 device types at once")
    private List<String> types;
}