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

package dynamic.mapper.explorer;

import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.explorer.ExplorerMessage;
import dynamic.mapper.explorer.ExplorerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/explorer")
@Tag(name = "Message Explorer Controller", description = "API for live exploration of raw inbound messages from broker connectors")
public class ExplorerController {

    /**
     * An explorer session reads raw device payloads for any topic on the tenant (wildcards
     * included) and, for OUTBOUND, creates real Notification 2.0 subscriptions as a side effect —
     * the same class of capability {@code NotificationSubscriptionController} gates, so it is
     * gated identically rather than being left at "any authenticated tenant user".
     */
    private static final String ADMIN_CREATE_ROLES = "hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')";

    private final ExplorerService explorerService;
    private final ContextService<UserCredentials> contextService;

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
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startSession(@Valid @RequestBody StartSessionRequest request) {
        String tenant = contextService.getContext().getTenant();
        String userId = contextService.getContext().getUsername();
        // connectorIdentifier is required for INBOUND sessions
        boolean isOutbound = "OUTBOUND".equalsIgnoreCase(request.getDirection());
        if (!isOutbound && (request.getConnectorIdentifier() == null || request.getConnectorIdentifier().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "connectorIdentifier is required for INBOUND sessions"));
        }
        // Without a sourceId or deviceType, OUTBOUND sessions create no Notification 2.0
        // subscription and rely entirely on messages already flowing through an unrelated active
        // mapping — i.e. the session would silently capture nothing. Require one of the two.
        if (isOutbound
                && (request.getSourceId() == null || request.getSourceId().isBlank())
                && (request.getDeviceType() == null || request.getDeviceType().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceId or deviceType is required for OUTBOUND sessions"));
        }
        try {
            String sessionId = explorerService.startSession(
                    tenant,
                    userId,
                    request.getConnectorIdentifier(),
                    request.getTopic(),
                    request.getMaxMessages(),
                    request.getDirection(),
                    request.getSourceId(),
                    request.getDeviceType(),
                    request.getSessionTTLMinutes());
            log.info("{} - Explorer session created: {}", tenant, sessionId);
            String subscriptionWarning = explorerService.getSubscriptionWarning(tenant, sessionId);
            Map<String, String> body = new java.util.HashMap<>();
            body.put("sessionId", sessionId);
            if (subscriptionWarning != null) {
                body.put("subscriptionWarning", subscriptionWarning);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (ConnectorRegistryException e) {
            log.warn("{} - Connector not found: {}", tenant, request.getConnectorIdentifier());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Connector not found: " + request.getConnectorIdentifier()));
        } catch (Exception e) {
            log.error("{} - Failed to start explorer session", tenant, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @Operation(summary = "Stop an explorer session",
            description = "Terminates the session and unregisters its listener from the connector.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Session stopped"),
            @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
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
    @PreAuthorize(ADMIN_CREATE_ROLES)
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
    @PreAuthorize(ADMIN_CREATE_ROLES)
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
