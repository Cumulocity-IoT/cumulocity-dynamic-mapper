/*
 * Copyright (c) 2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.ai.AIAgentService;
import dynamic.mapper.ai.MappingResultTools;
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

    /** Names of all tools the service registers with its MCP server (see App#tools). */
    private static Set<String> registeredToolNames() {
        Set<String> names = new HashSet<>();
        for (Class<?> toolClass : List.of(AIAgentService.class, MappingResultTools.class)) {
            for (Method method : toolClass.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null)
                    names.add(tool.name());
            }
        }
        return names;
    }

    private void assertAgentToolsAreExposedAndRegistered(String agentFile, Set<String> expectedTools) throws Exception {
        JsonNode manifest = objectMapper.readTree(Files.readString(Path.of("src/main/configuration/cumulocity.json")));
        JsonNode agent = objectMapper.readTree(new ClassPathResource(agentFile).getContentAsByteArray());
        JsonNode mcp = agent.path("mcp").get(0);
        assertEquals(manifest.path("exposeMcpServers").get(0).path("name").asText(),
                mcp.path("serverName").asText());

        Set<String> tools = new HashSet<>();
        mcp.path("tools").forEach(t -> tools.add(t.asText()));
        assertEquals(expectedTools, tools);
        // An agent naming a tool the MCP server does not serve would fail at the first call.
        assertTrue(registeredToolNames().containsAll(tools), "unregistered tool in " + agentFile);
    }

    @Test
    void jsonataAgentReferencesExposedMcpServerAndTools() throws Exception {
        assertAgentToolsAreExposedAndRegistered("agents/jsonata_agent.json",
                Set.of("evaluate-jsonata-expression", MappingResultTools.SUBMIT_SUBSTITUTIONS_TOOL_NAME));
    }

    @Test
    void smartFunctionAgentReferencesExposedMcpServerAndTool() throws Exception {
        assertAgentToolsAreExposedAndRegistered("agents/smartfunction_agent.json",
                Set.of(MappingResultTools.SUBMIT_SMART_FUNCTION_TOOL_NAME));
    }

    @Test
    void otherClasspathResourcesAreNotExposed() throws Exception {
        mockMvc.perform(get("/agents/application.properties")).andExpect(status().isNotFound());
        mockMvc.perform(get("/agents/unknown.json")).andExpect(status().isNotFound());
    }
}
