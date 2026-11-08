/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cumulocity.sdk.client.SDKException;

class C8YAgentSendFailureTextTest {

    @Test
    void theReasonComesBeforeThePayloadSoItSurvivesTruncation() {
        String text = C8YAgent.sendFailureText("MEASUREMENT", "{\"c8y_TemperatureMeasurement\":{}}",
                new SDKException(422, "Unprocessable Entity"));

        assertEquals("Could not send payload to c8y (HTTP 422 - Unprocessable Entity): "
                + "MEASUREMENT/{\"c8y_TemperatureMeasurement\":{}}", text);
        assertTrue(text.indexOf("HTTP 422") < text.indexOf("{"));
    }
}
