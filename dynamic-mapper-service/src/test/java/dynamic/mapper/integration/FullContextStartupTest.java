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

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.notification.NotificationSubscriber;

/**
 * Starts the full application context with every bean created eagerly, as in production
 * ({@code application.properties} sets {@code spring.main.lazy-initialization=false}).
 * <p>
 * The other Spring Boot tests run with lazy initialization and replace {@code C8YAgent} and
 * {@code MappingService} with mocks, so neither a dependency cycle nor a missing bean would show up
 * there. No bean is mocked here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.main.lazy-initialization=false",
        "spring.main.allow-circular-references=false"
})
@ActiveProfiles("test")
class FullContextStartupTest {

    @Autowired
    private BootstrapService bootstrapService;
    @Autowired
    private ServiceRegistry serviceRegistry;
    @Autowired
    private C8YAgent c8yAgent;
    @Autowired
    private MappingService mappingService;
    @Autowired
    private NotificationSubscriber notificationSubscriber;

    @Test
    void contextStartsWithEagerInitializationAndNoCircularReferences() {
        assertNotNull(bootstrapService);
        assertNotNull(serviceRegistry);
        assertNotNull(c8yAgent);
        assertNotNull(mappingService);
        assertNotNull(notificationSubscriber);
    }
}
