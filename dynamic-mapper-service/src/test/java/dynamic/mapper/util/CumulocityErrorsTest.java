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
 */

package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.cumulocity.sdk.client.SDKException;

class CumulocityErrorsTest {

    @Test
    void status502_isTransient() {
        assertTrue(CumulocityErrors.isTransientPlatformError(new SDKException(502, "Bad Gateway")));
    }

    @Test
    void status503_isTransient() {
        assertTrue(CumulocityErrors.isTransientPlatformError(new SDKException(503, "Service Unavailable")));
    }

    @Test
    void status504_isTransient() {
        assertTrue(CumulocityErrors.isTransientPlatformError(new SDKException(504, "Gateway Timeout")));
    }

    @Test
    void status403_isNotTransient() {
        assertFalse(CumulocityErrors.isTransientPlatformError(new SDKException(403, "Forbidden")));
    }

    @Test
    void plainRuntimeException_isNotTransient() {
        assertFalse(CumulocityErrors.isTransientPlatformError(new RuntimeException("boom")));
    }

    @Test
    void wrappedTransientError_isDetectedViaCauseChain() {
        RuntimeException wrapper = new RuntimeException("wrapped", new SDKException(502, "Bad Gateway"));

        assertTrue(CumulocityErrors.isTransientPlatformError(wrapper));
        SDKException found = CumulocityErrors.findTransientPlatformError(wrapper).orElseThrow();
        assertEquals(502, found.getHttpStatus());
    }

    @Test
    void firstLine_multiLineMessage_returnsOnlyFirstLine() {
        String message = "Http status code: 502\nReceived non-JSON error response (status=502, content-type=text/plain).";

        assertEquals("Http status code: 502", CumulocityErrors.firstLine(message));
    }

    @Test
    void firstLine_singleLineMessage_returnsItUnchanged() {
        assertEquals("Forbidden", CumulocityErrors.firstLine("Forbidden"));
    }

    @Test
    void firstLine_nullMessage_returnsEmptyString() {
        assertEquals("", CumulocityErrors.firstLine(null));
    }
}
