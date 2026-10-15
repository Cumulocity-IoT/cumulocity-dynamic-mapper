/*
 * Copyright (c) 2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agents")
public class AgentDefinitionController {

    @GetMapping(value = "/jsonata_agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource getJsonataAgent() {
        return new ClassPathResource("agents/jsonata_agent.json");
    }

    @GetMapping(value = "/smartfunction_agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Resource getSmartFunctionAgent() {
        return new ClassPathResource("agents/smartfunction_agent.json");
    }
}
