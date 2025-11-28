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
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.core;

import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.sdk.client.RestConnector;
import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.model.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;

@Service
@Slf4j
public class AIAgentService {

    @Autowired
    private ContextService<MicroserviceCredentials> contextService;

    @Autowired
    MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    CumulocityClientProperties clientProperties;

    @Autowired
    public RestConnector restConnector;

    private static final String DEFAULT_JSONATA_AGENT_NAME = "dynamic-mapper-jsonata-agent";
    private static final String DEFAULT_JAVASCRIPT_AGENT_NAME = "dynamic-mapper-javascript-agent";
    private static final String DEFAULT_SMART_FUNCTION_AGENT_NAME = "dynamic-mapper-smart-function-agent";
    private static final String MCP_SSE_ENDPOINT = "/service/dynamic-mapper-service/sse";
    private static final String JSONATA_TOOL_NAME = "evaluate_jsonata_expression";
    private static final String MCP_SERVER_NAME = "Dynamic Mapper MCP Server";

    public void initializeAIAgents() {
        if (checkAIAgentAvailable()) {
            MCPServer mcpServer = null;
            ResponseEntity<MCPServers> mcpServerResponse = getMCPServer();
            if (mcpServerResponse != null && mcpServerResponse.getStatusCode().is2xxSuccessful()
                    && mcpServerResponse.getBody() != null) {
                MCPServers mcpServers = mcpServerResponse.getBody();
                if (mcpServers.getServers().isEmpty()) {
                    log.info("{} - No MCP Servers found in AI Agent Manager, creating MCP Server...",
                            contextService.getContext().getTenant());
                    mcpServer = new MCPServer();
                    mcpServer.setUrl(clientProperties.getBaseURL() + MCP_SSE_ENDPOINT);
                    mcpServer.setName(MCP_SERVER_NAME);
                    mcpServer.setDescription("MCP Server for dynamic mapper service");
                    mcpServer.setIsDefault(false);
                    try {
                        ResponseEntity<String> response = createMCPServer(mcpServer);
                        if (response != null && !response.getStatusCode().is2xxSuccessful()) {
                            log.error("{} - Failed to create MCP Server: {}", contextService.getContext().getTenant(),
                                    response.getBody());
                        } else {
                            log.info("{} - MCP Server created successfully", contextService.getContext().getTenant());
                        }
                    } catch (Exception e) {
                        log.error("{} - Failed to create MCP Server", contextService.getContext().getTenant(), e);
                    }

                } else {
                    if (mcpServers.getServers().stream()
                            .anyMatch(server -> server.getUrl().equals(clientProperties.getBaseURL() + MCP_SSE_ENDPOINT)
                                    || server.getName().equals(MCP_SERVER_NAME))) {
                        log.info("{} - MCP Server already exists, not re-creating it",
                                contextService.getContext().getTenant());
                        mcpServer = mcpServers.getServers().stream()
                                .filter(server -> server.getUrl()
                                        .equals(clientProperties.getBaseURL() + MCP_SSE_ENDPOINT)
                                        || server.getName().equals(MCP_SERVER_NAME))
                                .findFirst()
                                .orElse(null);
                    } else {
                        log.info("{} - MCP Server not found, creating MCP Server...",
                                contextService.getContext().getTenant());
                        mcpServer = new MCPServer();
                        mcpServer.setUrl(clientProperties.getBaseURL() + MCP_SSE_ENDPOINT);
                        mcpServer.setName(MCP_SERVER_NAME);
                        mcpServer.setDescription("MCP Server for dynamic mapper service");
                        mcpServer.setIsDefault(false);
                        try {
                            ResponseEntity<String> response = createMCPServer(mcpServer);
                            if (response != null && !response.getStatusCode().is2xxSuccessful()) {
                                log.error("{} - Failed to create MCP Server: {}",
                                        contextService.getContext().getTenant(), response.getBody());
                            } else {
                                log.info("{} - MCP Server created successfully",
                                        contextService.getContext().getTenant());
                            }
                        } catch (Exception e) {
                            log.error("{} - Failed to create MCP Server", contextService.getContext().getTenant(), e);
                        }
                    }
                }
            } else {
                log.info("{} - Failed to retrieve MCP Servers", contextService.getContext().getTenant());
            }

            ResponseEntity<AIAgent[]> response = getAIAgents();
            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<AIAgent> agents = Arrays.asList(response.getBody());
                if (agents.isEmpty()) {
                    log.info("{} - No AIAgents found, creating default agents",
                            contextService.getContext().getTenant());
                    createDefaultAIAgents(mcpServer);
                } else {
                    if (agents.stream().anyMatch(agent -> agent.getName().equals(DEFAULT_JSONATA_AGENT_NAME)
                            || agent.getName().equals(DEFAULT_JAVASCRIPT_AGENT_NAME) || agent.getName().equals(DEFAULT_SMART_FUNCTION_AGENT_NAME))) {
                        log.info("{} - AIAgents already exists, not re-creating them",
                                contextService.getContext().getTenant());
                    } else {
                        log.info("{} - AIAgents not found, creating AI agents...",
                                contextService.getContext().getTenant());
                        createDefaultAIAgents(mcpServer);
                    }
                }
            } else {
                log.info("{} - Failed to retrieve AIAgents", contextService.getContext().getTenant());
            }
        }

    }

    public boolean checkAIAgentAvailable() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
        ResponseEntity<String> response = null;
        try {
            String serverUrl = clientProperties.getBaseURL() + "/service/ai/health";
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            response = restTemplate.exchange(serverUrl, HttpMethod.GET, requestEntity, String.class);
        } catch (Exception e) {
            log.info("{} - AI Agent Manager is not available. AI capabilities won't be available", tenant);
            return false;
        }
        if (response != null && response.getStatusCode().is2xxSuccessful()) {
            log.info("{} - AIAgent is available", tenant);
            return true;
        } else {
            log.info("{} - AI Agent Manager is not available. AI capabilities won't be available", tenant);
            return false;
        }
    }

    /*
     * public boolean checkMCPServerAvailable() {
     * HttpHeaders headers = new HttpHeaders();
     * headers.set("Authorization",
     * contextService.getContext().toCumulocityCredentials().getAuthenticationString
     * ());
     * headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
     * String tenant =
     * contextService.getContext().toCumulocityCredentials().getTenantId();
     * ResponseEntity<String> response = null;
     * try {
     * String serverUrl = clientProperties.getBaseURL() + MCP_HEALTH_ENDPOINT;
     * RestTemplate restTemplate = new RestTemplate();
     * HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
     * response = restTemplate.exchange(serverUrl, HttpMethod.GET, requestEntity,
     * String.class);
     * } catch (Exception e) {
     * log.info("{} - MCP-Server is not available", tenant);
     * }
     * if (response != null && response.getStatusCode().is2xxSuccessful()) {
     * log.info("{} - MCP-Server is available", tenant);
     * return true;
     * } else {
     * log.info("{} - MCP-Server is not available", tenant);
     * return false;
     * }
     * }
     */

    public void createDefaultAIAgents(MCPServer mcpServer) {
        HashMap<String, String> prompts = getAgentPrompts();

        prompts.keySet().forEach(file -> {
            AIAgent aiAgent = new AIAgent();
            if (file.equals("jsonata"))
                aiAgent.setName(DEFAULT_JSONATA_AGENT_NAME);
            if (file.equals("javascript"))
                aiAgent.setName(DEFAULT_JAVASCRIPT_AGENT_NAME);
            if (file.equals("smartfunction"))
                aiAgent.setName(DEFAULT_SMART_FUNCTION_AGENT_NAME);

            Agent agent = new Agent();
            agent.setMaxSteps(50);
            agent.setSystem(prompts.get(file));
            aiAgent.setAgent(agent);
            aiAgent.setType("text");
            if (mcpServer != null && aiAgent.getName().equals(DEFAULT_JSONATA_AGENT_NAME)) {
                MCPUsage tools = new MCPUsage();
                tools.setServerName(mcpServer.getName());
                tools.setTools(new String[] { JSONATA_TOOL_NAME });
                aiAgent.setMcp(List.of(tools));
            }
            ResponseEntity<String> response = createAIAgent(aiAgent);
            if (!response.getStatusCode().is2xxSuccessful())
                log.error("{} - Failed to create AIAgent: {}", contextService.getContext().getTenant(),
                        response.getBody());
        });
    }

    private HashMap<String, String> getAgentPrompts() {
        Resource[] resources;
        HashMap<String, String> prompts = new HashMap<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            resources = resolver.getResources("classpath:prompts/*.txt");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName.startsWith("jsonata"))
                    prompts.put("jsonata",
                            new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                if (fileName.startsWith("javascript"))
                    prompts.put("javascript",
                            new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                if (fileName.startsWith("smartfunction"))
                    prompts.put("smartfunction",
                            new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            }
        } catch (Exception e) {
            log.error("{} - Failed to load template resources", contextService.getContext().getTenant(), e);
        }
        return prompts;
    }

    public ResponseEntity<String> createAIAgent(AIAgent aiAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
        ResponseEntity<String> response = null;
        try {
            String serverUrl = clientProperties.getBaseURL() + "/service/ai/agent/text";
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<AIAgent> requestEntity = new HttpEntity<>(aiAgent, headers);
            response = restTemplate.exchange(serverUrl, HttpMethod.POST, requestEntity, String.class);
        } catch (Exception e) {
            log.info("{} - AIAgent creation failed", tenant);
        }
        return response;
    }

    public ResponseEntity<AIAgent[]> getAIAgents() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
        try {
            String serverUrl = clientProperties.getBaseURL() + "/service/ai/agent";
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            return restTemplate.exchange(serverUrl, HttpMethod.GET, requestEntity, AIAgent[].class);
        } catch (Exception e) {
            log.info("{} - AIAgent retrieval failed", tenant);
        }
        return null;
    }

    public ResponseEntity<String> createMCPServer(MCPServer mcpServer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
        ResponseEntity<String> response = null;
        try {
            String serverUrl = clientProperties.getBaseURL() + "/service/ai/mcp/servers";
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<MCPServer> requestEntity = new HttpEntity<>(mcpServer, headers);
            response = restTemplate.exchange(serverUrl, HttpMethod.POST, requestEntity, String.class);
        } catch (Exception e) {
            log.error("{} - MCPServer creation failed", tenant, e);
            throw e;
        }
        return response;
    }

    public ResponseEntity<MCPServers> getMCPServer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
        try {
            String serverUrl = clientProperties.getBaseURL() + "/service/ai/mcp/servers";
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            return restTemplate.exchange(serverUrl, HttpMethod.GET, requestEntity, MCPServers.class);
        } catch (Exception e) {
            log.error("{} - MCPServer creation failed", tenant, e);
        }
        return null;
    }

    /**
     * Test a JSONata expression against a JSON string.
     * 
     * @param expression JSONata expression to be evaluated against the source JSON
     * @param sourceJSON JSON string to be used as source for the JSONata expression
     *                   evaluation
     * @return The result of the JSONata expression evaluation as a pretty-printed
     *         JSON string
     * @throws RuntimeException         if the evaluation fails
     * @throws IllegalArgumentException if the expression or source JSON is null or
     *                                  empty
     */
    @Tool(description = "Evaluate a JSONata expression against a JSON object")
    public String evaluateJsonataExpression(String expression, String sourceJSON) {
        if (expression == null || expression.isEmpty())
            throw new IllegalArgumentException("JSONata expression cannot be null");
        if (sourceJSON == null || sourceJSON.isEmpty())
            throw new IllegalArgumentException("Source JSON cannot be null");
        try {
            var expr = jsonata(expression);
            Object parsedJson = Json.parseJson(sourceJSON);
            Object result = expr.evaluate(parsedJson);
            return toPrettyJsonString(result);
        } catch (Exception e) {
            log.error("Error evaluating JSONata expression: ", e);
            throw new RuntimeException(e);
        }
    }
}
