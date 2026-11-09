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

package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;

@ExtendWith(MockitoExtension.class)
class NotificationSubscriptionControllerTest {

    @Mock
    private C8YAgent c8yAgent;
    @Mock
    private ContextService<UserCredentials> contextService;
    @Mock
    private ServiceRegistry serviceRegistry;
    @Mock
    private ServiceConfigurationService serviceConfigurationService;
    @Mock
    private NotificationSubscriptionService subscriptionService;

    private NotificationSubscriptionController controller;

    @BeforeEach
    void setUp() {
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn("t1");
        when(contextService.getContext()).thenReturn(credentials);
        ServiceConfiguration config = mock(ServiceConfiguration.class);
        when(config.getOutboundMappingEnabled()).thenReturn(true);
        when(serviceConfigurationService.getServiceConfiguration("t1")).thenReturn(config);
        controller = new NotificationSubscriptionController(c8yAgent, contextService, serviceRegistry, org.mockito.Mockito.mock(dynamic.mapper.notification.NotificationSubscriber.class),
                serviceConfigurationService, subscriptionService);
    }

    @Test
    void updateSubscription_missingDeviceList_is400() {
        NotificationSubscriptionRequest request = new NotificationSubscriptionRequest();
        request.setApi(API.ALL);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.updateSubscription(request, Utils.STATIC_DEVICE_SUBSCRIPTION));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void updateGroupSubscription_missingDeviceList_is400() {
        NotificationSubscriptionRequest request = new NotificationSubscriptionRequest();
        request.setApi(API.ALL);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.updateGroupSubscription(request));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void updateSubscription_emptyDeviceList_isAllowedAndClearsAll() {
        NotificationSubscriptionRequest request = new NotificationSubscriptionRequest();
        request.setApi(API.ALL);
        request.setDevices(new java.util.ArrayList<>());
        when(subscriptionService.updateDeviceSubscription("t1", request, Utils.STATIC_DEVICE_SUBSCRIPTION))
                .thenReturn(NotificationSubscriptionResponse.builder().build());

        assertEquals(HttpStatus.OK,
                controller.updateSubscription(request, Utils.STATIC_DEVICE_SUBSCRIPTION).getStatusCode());
    }
}
