/*
 * Copyright (c) 2025 Cumulocity GmbH.
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
 *  @authors Christof Strack
 *
 */

package dynamic.mapper.controller;

import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.model.ExplorerMessage;
import dynamic.mapper.service.ExplorerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/explorer")
@Tag(name = "Message Explorer Controller", description = "API for live exploration of raw inbound messages from broker connectors")
public class ExplorerController {

    @Autowired
    private ExplorerService explorerService;

    @Autowired
    private ContextService<UserCredentials> contextService;

    // ---- DTOs ---------------------------------------------------------------

    @Data
    public static class StartSessionRequest {
        @Schema(description = "Identifier of the inbound connector to listen on (required for INBOUND, ignored for OUTBOUND)", example = "mqtt-broker-01")
        private String connectorIdentifier;

        @NotBlank
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Topic to subscribe to (MQTT wildcards supported)", example = "sensors/#")
        private String topic;

        @Schema(description = "Maximum number of messages to buffer (1–500). Defaults to 50.", example = "50")
        private int maxMessages = 50;

        @Schema(description = "Direction to capture: INBOUND (broker → C8Y) or OUTBOUND (C8Y → broker). Defaults to INBOUND.",
                allowableValues = {"INBOUND", "OUTBOUND"}, example = "INBOUND")
        private String direction = "INBOUND";

        @Schema(description = "C8Y managed object ID (device or group) for outbound notifications (OUTBOUND only; required — without a source ID no Notification 2.0 subscription is created and no events will be captured).", example = "12345")
        private String sourceId;
    }

    // ---- Endpoints ----------------------------------------------------------

    @Operation(summary = "Start an explorer session",
            description = "Creates a new session that captures raw inbound messages from the specified connector and topic.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Session created",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object", example = "{\"sessionId\":\"<uuid>\"}"))),
            @ApiResponse(responseCode = "404", description = "Connector not found"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startSession(@Valid @RequestBody StartSessionRequest request) {
        String tenant = contextService.getContext().getTenant();
        // connectorIdentifier is required for INBOUND sessions
        boolean isOutbound = "OUTBOUND".equalsIgnoreCase(request.getDirection());
        if (!isOutbound && (request.getConnectorIdentifier() == null || request.getConnectorIdentifier().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorIdentifier is required for INBOUND sessions"));
        }
        try {
            String sessionId = explorerService.startSession(
                    tenant,
                    request.getConnectorIdentifier(),
                    request.getTopic(),
                    request.getMaxMessages(),
                    request.getDirection(),
                    request.getSourceId());
            log.info("{} - Explorer session created: {}", tenant, sessionId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sessionId", sessionId));
        } catch (ConnectorRegistryException e) {
            log.warn("{} - Connector not found: {}", tenant, request.getConnectorIdentifier());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Connector not found: " + request.getConnectorIdentifier()));
        } catch (Exception e) {
            log.error("{} - Failed to start explorer session", tenant, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Stop an explorer session",
            description = "Terminates the session and unregisters its listener from the connector.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Session stopped"),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> stopSession(
            @Parameter(description = "Session ID returned by POST /explorer/session", required = true)
            @PathVariable @NotNull String sessionId) {
        String tenant = contextService.getContext().getTenant();
        if (!explorerService.sessionExists(tenant, sessionId)) {
            return ResponseEntity.notFound().build();
        }
        explorerService.stopSession(tenant, sessionId);
        log.info("{} - Explorer session stopped via API: {}", tenant, sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Poll buffered messages",
            description = "Returns all messages captured since the session started (or since the last clear). Updates the session TTL.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Messages returned",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ExplorerMessage.class)))),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @GetMapping(value = "/session/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExplorerMessage>> getMessages(
            @Parameter(description = "Session ID", required = true)
            @PathVariable @NotNull String sessionId) {
        String tenant = contextService.getContext().getTenant();
        if (!explorerService.sessionExists(tenant, sessionId)) {
            return ResponseEntity.notFound().build();
        }
        List<ExplorerMessage> messages = explorerService.getMessages(tenant, sessionId);
        return ResponseEntity.ok(messages);
    }

    @Operation(summary = "Clear buffered messages",
            description = "Discards all captured messages in the session buffer without stopping the session.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Messages cleared"),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @DeleteMapping("/session/{sessionId}/messages")
    public ResponseEntity<Void> clearMessages(
            @Parameter(description = "Session ID", required = true)
            @PathVariable @NotNull String sessionId) {
        String tenant = contextService.getContext().getTenant();
        if (!explorerService.sessionExists(tenant, sessionId)) {
            return ResponseEntity.notFound().build();
        }
        explorerService.clearMessages(tenant, sessionId);
        return ResponseEntity.noContent().build();
    }
}
