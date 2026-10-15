/*
 * Copyright (c) 2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.ai.AIAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentDefinitionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentDefinitionController()).build();

    @Test
    void everyManifestAgentIsServedAsJsonWithMatchingName() throws Exception {
        JsonNode manifest = objectMapper.readTree(Files.readString(Path.of("src/main/configuration/cumulocity.json")));
        JsonNode agents = manifest.path("agents");
        assertEquals(2, agents.size());
        Iterator<Map.Entry<String, JsonNode>> entries = agents.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String path = entry.getValue().asText();
            byte[] expected = new ClassPathResource(path).getContentAsByteArray();
            String response = mockMvc.perform(get("/" + path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().bytes(expected))
                    .andReturn().getResponse().getContentAsString();
            JsonNode agent = objectMapper.readTree(response);
            assertEquals(entry.getKey(), agent.path("name").asText());
            assertEquals("text", agent.path("type").asText());
            assertFalse(agent.path("agent").path("system").asText().isBlank());
        }
    }

    @Test
    void jsonataAgentReferencesExposedMcpServerAndTool() throws Exception {
        JsonNode manifest = objectMapper.readTree(Files.readString(Path.of("src/main/configuration/cumulocity.json")));
        JsonNode agent = objectMapper.readTree(new ClassPathResource("agents/jsonata_agent.json")
                .getContentAsByteArray());
        JsonNode mcp = agent.path("mcp").get(0);
        assertEquals(manifest.path("exposeMcpServers").get(0).path("name").asText(),
                mcp.path("serverName").asText());
        String toolName = AIAgentService.class.getMethod("evaluateJsonataExpression", String.class, String.class)
                .getAnnotation(Tool.class).name();
        assertEquals(1, mcp.path("tools").size());
        assertEquals(toolName, mcp.path("tools").get(0).asText());
    }

    @Test
    void otherClasspathResourcesAreNotExposed() throws Exception {
        mockMvc.perform(get("/agents/application.properties")).andExpect(status().isNotFound());
        mockMvc.perform(get("/agents/unknown.json")).andExpect(status().isNotFound());
    }
}
