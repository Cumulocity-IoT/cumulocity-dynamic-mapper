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
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.web.bind.annotation.RequestMethod;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Represents a single API request generated during mapping transformation")
public class DynamicMapperRequest {
    @Builder.Default
    @Schema(description = "Index of the preceding request in a chain (-1 if none)", example = "-1")
    private int predecessor = -1;
    @Schema(description = "HTTP method used for the request", example = "POST")
    private RequestMethod method;
    @Schema(description = "Target Cumulocity IoT API", implementation = API.class, example = "MEASUREMENT")
    private API api;
    @Schema(description = "MQTT topic to publish to (for broker connectors)", example = "device/sensor01/command")
    private String publishTopic;
    @Schema(description = "Whether the MQTT message should be retained", example = "false")
    private Boolean retain;
    @Schema(description = "Cumulocity internal device source ID", example = "12345")
    private String sourceId;
    @Schema(description = "External device identifier", example = "sensor-berlin-01")
    private String externalId;
    @Schema(description = "Type of the external device identifier", example = "c8y_Serial")
    private String externalIdType;
    @Schema(description = "Raw request payload sent to the target system")
    private String request;
    @Schema(description = "Request payload with Cumulocity source identifier populated (for internal connectors)")
    private String requestCumulocity;
    @Schema(description = "Path used for the Cumulocity API request", example = "/measurement/measurements")
    private String pathCumulocity;
    @Schema(description = "Response received from the target system")
    private String response;
    @Schema(description = "Error that occurred during request execution, if any")
    private Exception error;
    // this property documents if a C8Y request was already submitted and is created only for documentation/testing purpose.
    // this happens when a device is created implicitly with mapping.createNonExistingDevice == true
    // private Boolean alreadySubmitted;
    public boolean hasError() {
        return error != null;
    }
}
