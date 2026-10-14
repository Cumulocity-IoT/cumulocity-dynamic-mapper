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
package dynamic.mapper.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
@Schema(description = "Data to test mapping with payload in backend")
@ToString()
public class TestContext {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Mapping to test")
    @NotNull
    private Mapping mapping;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "JSON payload as string for test")
    @NotNull
    private String payload;

    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "Send payload to backend")
    @NotNull
    private Boolean send = false;

    /**
     * @deprecated Ignored. A live test (send=true) of an inbound mapping always runs against a test
     *             device: one is created (tagged d11r_testDevice), or the existing device with the
     *             same external ID is used. Kept so existing clients that still send the field keep working.
     */
    @Deprecated
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, deprecated = true, description = "Ignored. A live test (send=true) of an inbound mapping always uses a test device: created if missing (tagged with d11r_testDevice), otherwise the existing device with the same external ID.")
    private Boolean createTestDevice = false;
}
