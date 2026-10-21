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

package dynamic.mapper.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.RepairStrategy;

/**
 * MCP tools through which the mapping AI agents hand over their <em>final</em> result.
 *
 * <p>The tools do not store anything: the UI's AI drawer reads the result from the tool call's
 * <em>input</em> (the Agent Chat component's {@code onToolResult} event), which is why the agent
 * gets a structured, unambiguous channel instead of the UI scraping fenced code blocks out of the
 * reply text. The only job done here is a cheap sanity check; a thrown exception comes back to the
 * agent as a tool error, so it can correct itself and submit again.
 */
@Component
public class MappingResultTools {

    public static final String SUBMIT_SMART_FUNCTION_TOOL_NAME = "submit-smart-function";
    public static final String SUBMIT_SUBSTITUTIONS_TOOL_NAME = "submit-substitutions";

    /** One JSONata substitution, as submitted by the agent. */
    public record SubstitutionInput(
            @ToolParam(description = "JSONata expression that extracts/transforms data from the source template", required = true) String pathSource,
            @ToolParam(description = "Dot-notation path in the target template where the result is placed", required = true) String pathTarget,
            @ToolParam(description = "true if an array result should be expanded into multiple target elements; default false", required = false) Boolean expandArray,
            @ToolParam(description = "One of DEFAULT, USE_FIRST_VALUE_OF_ARRAY, USE_LAST_VALUE_OF_ARRAY, IGNORE, REMOVE_IF_MISSING_OR_NULL; default DEFAULT", required = false) String repairStrategy) {
    }

    @Tool(name = SUBMIT_SMART_FUNCTION_TOOL_NAME, description = "Submit the final Smart Function code to the mapping editor. "
            + "Call this exactly once per answer, with the complete function — not a fragment and not an example.")
    public String submitSmartFunction(
            @ToolParam(description = "Complete JavaScript source of the Smart Function, containing `function onMessage(msg, context)`", required = true) String code) {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("code must not be empty");
        if (!code.contains("function onMessage"))
            throw new IllegalArgumentException(
                    "code must define `function onMessage(msg, context)`; submit the complete function");
        return "Smart Function submitted to the mapping editor.";
    }

    @Tool(name = SUBMIT_SUBSTITUTIONS_TOOL_NAME, description = "Submit the final set of JSONata substitutions to the mapping editor. "
            + "Call this exactly once per answer, with the complete set — it replaces the mapping's current substitutions.")
    public String submitSubstitutions(
            @ToolParam(description = "The complete list of substitutions", required = true) List<SubstitutionInput> substitutions) {
        if (substitutions == null || substitutions.isEmpty())
            throw new IllegalArgumentException("substitutions must not be empty");

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < substitutions.size(); i++) {
            SubstitutionInput s = substitutions.get(i);
            if (s == null || s.pathSource() == null || s.pathSource().isBlank())
                problems.add("substitution " + (i + 1) + ": pathSource must not be empty");
            if (s == null || s.pathTarget() == null || s.pathTarget().isBlank())
                problems.add("substitution " + (i + 1) + ": pathTarget must not be empty");
            if (s != null && s.repairStrategy() != null && !isRepairStrategy(s.repairStrategy()))
                problems.add("substitution " + (i + 1) + ": unknown repairStrategy '" + s.repairStrategy() + "'");
        }
        if (!problems.isEmpty())
            throw new IllegalArgumentException(String.join("; ", problems));
        return substitutions.size() + " substitution(s) submitted to the mapping editor.";
    }

    private static boolean isRepairStrategy(String value) {
        for (RepairStrategy strategy : RepairStrategy.values()) {
            if (strategy.name().equals(value))
                return true;
        }
        return false;
    }
}
