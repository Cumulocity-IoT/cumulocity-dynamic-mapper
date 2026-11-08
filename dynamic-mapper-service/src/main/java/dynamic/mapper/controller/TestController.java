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

package dynamic.mapper.controller;


import jakarta.servlet.http.HttpServletRequest;
import dynamic.mapper.mapping.MappingTestService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.model.TestResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestController
@Tag(name = "Test Controller", description = "Endpoints for testing mappings and webhook echo utilities")
public class TestController {

    private final MappingTestService mappingTestService;
    private final ContextService<UserCredentials> contextService;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;


    @Operation(summary = "Test a mapping", description = "Executes a mapping transformation against a provided payload and returns the generated requests and any warnings or errors. Optionally sends the result to Cumulocity IoT.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Test executed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TestResult.class))),
            @ApiResponse(responseCode = "400", description = "Invalid mapping or payload"),
            @ApiResponse(responseCode = "404", description = "Test connector not found"),
            @ApiResponse(responseCode = "500", description = "Test execution failed")
    })
    // Same roles as creating or updating a mapping: the caller supplies a complete Mapping in the
    // request body, and with send=true the result is dispatched to Cumulocity for real (for inbound
    // mappings against a test device, which is created if missing) using the microservice's own elevated
    // credentials. Without this, any authenticated tenant user could write measurements, events,
    // alarms and devices without holding any Dynamic Mapper role.
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @RequestMapping(value = "/test/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestResult> testMapping(
            @RequestBody TestContext context) {
        String tenant = contextService.getContext().getTenant();
        try {
            return new ResponseEntity<>(mappingTestService.runTest(tenant, context), HttpStatus.OK);
        } catch (MappingTestService.MappingTestException e) {
            HttpStatus status = switch (e.getKind()) {
                case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            throw new ResponseStatusException(status, e.getMessage());
        }
    }

    @Operation(summary = "Echo webhook input", description = "Accepts any POST request and returns the body unchanged. Useful for testing outbound webhook mappings.")
    @ApiResponse(responseCode = "200", description = "Input echoed back", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @PostMapping(value = "/webhook/echo/**", produces = MediaType.TEXT_PLAIN_VALUE)
    public String echoInput(HttpServletRequest request, @RequestBody String input) {
        // Get the full URL path
        String fullPath = request.getRequestURI();

        // Get query parameters if any
        String queryString = request.getQueryString();

        // Log path and input
        if (queryString != null) {
            log.info("Received request at path: {} with query parameters: {}", fullPath, queryString);
        } else {
            log.info("Received request at path: {}", fullPath);
        }
        // debug, not info: this endpoint echoes whatever is posted to it, so the body is arbitrary
        // third-party content that should not land in the tenant's log by default.
        log.debug("Received body: {}", input);

        return input;
    }

    @Operation(summary = "Webhook health check", description = "Returns 200 OK to confirm the webhook echo endpoint is reachable.")
    @ApiResponse(responseCode = "200", description = "Endpoint is healthy")
    @GetMapping("/webhook")
    public ResponseEntity<String> echoHealth(HttpServletRequest request) {
        // Get the full URL
        String url = request.getRequestURL().toString();

        // Get query string
        String queryString = request.getQueryString();

        // Log the full path with query parameters
        if (queryString != null) {
            log.info("Received request: {} with query parameters: {}", url, queryString);
        } else {
            log.info("Received request: {}", url);
        }

        // Return 200 OK with empty body
        return ResponseEntity.ok().build();
    }
}
