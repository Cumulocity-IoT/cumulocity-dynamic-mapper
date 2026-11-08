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

package dynamic.mapper.connector.httppolling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpPollingRequestHelperLinkTest {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPollingRequestHelperLinkTest.class);
    private static final String BASE = "https://api.example.com/v1";
    private static final URI REQUEST = URI.create("https://api.example.com/v1/devices");

    @Test
    void aRelativeLinkIsResolvedAgainstTheRequestUrlNotTheBaseUrl() {
        URI resolved = HttpPollingRequestHelper.resolveSameOrigin(URI.create("?page=2"), REQUEST, BASE, "t", LOG);

        assertEquals(URI.create("https://api.example.com/v1/devices?page=2"), resolved);
    }

    @Test
    void anAbsoluteLinkOnTheSameOriginIsAccepted() {
        URI next = URI.create("https://api.example.com/v1/devices?page=3");

        assertEquals(next, HttpPollingRequestHelper.resolveSameOrigin(next, REQUEST, BASE, "t", LOG));
    }

    @Test
    void aLinkToAnotherOriginIsRejected() {
        assertNull(HttpPollingRequestHelper.resolveSameOrigin(
                URI.create("https://evil.example.org/x"), REQUEST, BASE, "t", LOG));
    }
}
