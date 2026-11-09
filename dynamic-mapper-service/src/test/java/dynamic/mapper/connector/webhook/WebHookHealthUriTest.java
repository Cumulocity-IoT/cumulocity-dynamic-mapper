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

package dynamic.mapper.connector.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

import dynamic.mapper.connector.core.client.ConnectorException;

class WebHookHealthUriTest {

    private static URI resolve(String base, String health) throws ConnectorException {
        return WebHook.healthUri(health).apply(new DefaultUriBuilderFactory(base).builder());
    }

    @Test
    void relativePathStaysOnBaseHost() throws ConnectorException {
        assertEquals(URI.create("https://hooks.example.com/api/health"),
                resolve("https://hooks.example.com/api", "/health"));
        assertEquals(URI.create("https://hooks.example.com/health?deep=true"),
                resolve("https://hooks.example.com", "/health?deep=true"));
    }

    @Test
    void absoluteOrHostBearingValuesAreRejected() {
        for (String bad : new String[] { "http://169.254.169.254/latest/meta-data", "https://evil.example/x",
                "//evil.example/x", "file:///etc/passwd", "/health#frag" }) {
            assertThrows(ConnectorException.class, () -> WebHook.healthUri(bad), bad);
        }
    }
}
