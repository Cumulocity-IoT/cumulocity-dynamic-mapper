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
 */

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.mapping.MappingOrchestrationService;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.mapping.MappingValidator;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.model.validation.MappingValidationResult;
import dynamic.mapper.model.validation.ValidationError;

/** POST /mapping/validate: the create/update validation, answered as data and nothing persisted. */
class MappingControllerValidateTest {

    private static final String TENANT = "t1";

    private MappingService mappingService;
    private MappingController controller;

    @BeforeEach
    void setUp() throws Exception {
        MicroserviceSubscriptionsService subscriptions = mock(MicroserviceSubscriptionsService.class);
        when(subscriptions.callForTenant(eq(TENANT), any(java.util.concurrent.Callable.class)))
                .thenAnswer(inv -> ((java.util.concurrent.Callable<?>) inv.getArgument(1)).call());
        MappingValidator validator = new MappingValidator(subscriptions, null, null);

        mappingService = mock(MappingService.class);
        when(mappingService.validateMapping(eq(TENANT), any())).thenAnswer(inv -> {
            Mapping m = inv.getArgument(1);
            return validator.validate(TENANT, m, m.getId());
        });

        @SuppressWarnings("unchecked")
        ContextService<UserCredentials> context = mock(ContextService.class);
        UserCredentials creds = mock(UserCredentials.class);
        when(creds.getTenant()).thenReturn(TENANT);
        when(context.getContext()).thenReturn(creds);
        controller = new MappingController(mock(MappingOrchestrationService.class), mappingService, context);
    }

    private static Mapping.MappingBuilder valid() {
        return Mapping.builder().direction(Direction.INBOUND).mappingType(MappingType.JSON)
                .transformationType(TransformationType.SMART_FUNCTION).name("m").identifier("i1")
                .mappingTopic("a/+").mappingTopicSample("a/1").sourceTemplate("{}").targetTemplate("{}");
    }

    @Test
    void aValidMappingIsAnsweredWithValidTrue() {
        ResponseEntity<MappingValidationResult> response = controller.validateMapping(valid().build());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().valid());
        assertTrue(response.getBody().errors().isEmpty());
    }

    @Test
    void anInvalidMappingIsStillAnswered200WithTheCodesAndDetails() {
        Substitution broken = Substitution.builder().pathSource("temperature +").pathTarget("c8y_T.T.value").build();
        Mapping mapping = valid().transformationType(TransformationType.JSONATA)
                .mappingTopicSample("a/1/2").substitutions(new Substitution[] { broken }).build();

        ResponseEntity<MappingValidationResult> response = controller.validateMapping(mapping);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        MappingValidationResult body = response.getBody();
        assertFalse(body.valid());
        // A rule that can say where it failed supplies the detail, like the 422 body would.
        assertTrue(body.errors().contains(ValidationError.Substitution_Source_Expression_Must_Be_Valid_JSONata));
        assertTrue(body.errors().contains(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name));
        assertTrue(body.details().stream().anyMatch(d -> "substitutions[0].pathSource".equals(d.field())));
    }

    @Test
    void aRetiredTypeIsReported() {
        MappingValidationResult body = controller
                .validateMapping(valid().mappingType(MappingType.PROTOBUF_INTERNAL).build()).getBody();
        assertTrue(body.errors().contains(ValidationError.Retired_Mapping_Type_Or_Transformation_Type));
    }

    @Test
    void aMappingWithoutSubstitutionsDoesNotFail() {
        Mapping mapping = valid().build();
        mapping.setSubstitutions(null);
        ResponseEntity<MappingValidationResult> response = controller.validateMapping(mapping);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void theEndpointRequiresTheCreateOrAdminRole() throws Exception {
        PreAuthorize guard = MappingController.class.getMethod("validateMapping", Mapping.class)
                .getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')", guard.value());
    }
}
