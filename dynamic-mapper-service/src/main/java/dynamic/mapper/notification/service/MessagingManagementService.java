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
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.notification.service;

import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for the Cumulocity internal messaging-management REST API.
 * Provides subscriber listing and deletion for Notifications 2.0 topics.
 */
@Slf4j
@Service
public class MessagingManagementService {

    private static final String SUBSCRIBERS_PATH =
            "/service/messaging-management/tenants/%s/namespaces/relnotif/topics/%s/types/persistent/subscribers";

    @Autowired
    private CumulocityClientProperties clientProperties;

    @Autowired
    private ContextService<MicroserviceCredentials> contextService;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * List all subscriber names for the given Notifications 2.0 topic.
     * Returns an empty list if the topic does not exist or is unreachable.
     */
    public List<String> getSubscribers(String tenant, String topicName) {
        String url = clientProperties.getBaseURL()
                + String.format(SUBSCRIBERS_PATH, tenant, topicName);
        HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());

        try {
            ResponseEntity<JsonNode> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            return parseSubscriberList(response.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return Collections.emptyList();
            }
            log.warn("{} - Could not list subscribers for topic '{}': {}", tenant, topicName, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("{} - Could not list subscribers for topic '{}': {}", tenant, topicName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Delete a subscriber from a Notifications 2.0 topic.
     */
    public void deleteSubscriber(String tenant, String topicName, String subscriberName) {
        String url = clientProperties.getBaseURL()
                + String.format(SUBSCRIBERS_PATH + "/%s", tenant, topicName, subscriberName);
        HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());

        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            log.info("{} - Deleted subscriber '{}' from topic '{}'", tenant, subscriberName, topicName);
        } catch (Exception e) {
            log.warn("{} - Could not delete subscriber '{}' from topic '{}': {}",
                    tenant, subscriberName, topicName, e.getMessage());
        }
    }

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    private List<String> parseSubscriberList(JsonNode body) {
        List<String> result = new ArrayList<>();
        if (body == null) {
            return result;
        }
        // Handle plain array: ["sub1", "sub2", ...]
        if (body.isArray()) {
            body.forEach(node -> result.add(node.asText()));
        // Handle wrapped object: {"subscribers": [...]}
        } else if (body.has("subscribers") && body.get("subscribers").isArray()) {
            body.get("subscribers").forEach(node -> result.add(node.asText()));
        }
        return result;
    }
}
