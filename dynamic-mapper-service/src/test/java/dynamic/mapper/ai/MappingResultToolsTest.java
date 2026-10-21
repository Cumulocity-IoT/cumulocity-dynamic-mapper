package dynamic.mapper.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import dynamic.mapper.ai.MappingResultTools.SubstitutionInput;

class MappingResultToolsTest {

    private final MappingResultTools tools = new MappingResultTools();

    @Test
    void acceptsCompleteSmartFunction() {
        assertDoesNotThrow(() -> tools.submitSmartFunction("function onMessage(msg, context) { return null; }"));
    }

    @Test
    void rejectsSmartFunctionWithoutOnMessage() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> tools.submitSmartFunction("var x = 1;"));
        assertTrue(e.getMessage().contains("onMessage"));
    }

    @Test
    void rejectsBlankSmartFunction() {
        assertThrows(IllegalArgumentException.class, () -> tools.submitSmartFunction("  "));
        assertThrows(IllegalArgumentException.class, () -> tools.submitSmartFunction(null));
    }

    @Test
    void acceptsSubstitutionsWithOptionalFieldsOmitted() {
        assertDoesNotThrow(() -> tools.submitSubstitutions(
                List.of(new SubstitutionInput("a.b", "c.d", null, null))));
    }

    @Test
    void acceptsEveryRepairStrategyName() {
        for (var strategy : dynamic.mapper.model.RepairStrategy.values()) {
            assertDoesNotThrow(() -> tools.submitSubstitutions(
                    List.of(new SubstitutionInput("a", "b", false, strategy.name()))));
        }
    }

    @Test
    void rejectsEmptySubstitutionList() {
        assertThrows(IllegalArgumentException.class, () -> tools.submitSubstitutions(List.of()));
        assertThrows(IllegalArgumentException.class, () -> tools.submitSubstitutions(null));
    }

    @Test
    void reportsEverySubstitutionProblemAtOnce() {
        var e = assertThrows(IllegalArgumentException.class, () -> tools.submitSubstitutions(List.of(
                new SubstitutionInput("", "x", null, null),
                new SubstitutionInput("a", "b", null, "NOT_A_STRATEGY"))));
        assertTrue(e.getMessage().contains("substitution 1: pathSource"));
        assertTrue(e.getMessage().contains("substitution 2: unknown repairStrategy"));
    }

    @Test
    void exposesBothToolsWithTheirInputSchema() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        var byName = new java.util.HashMap<String, String>();
        for (ToolCallback cb : callbacks)
            byName.put(cb.getToolDefinition().name(), cb.getToolDefinition().inputSchema());

        assertTrue(byName.containsKey(MappingResultTools.SUBMIT_SMART_FUNCTION_TOOL_NAME));
        assertTrue(byName.get(MappingResultTools.SUBMIT_SMART_FUNCTION_TOOL_NAME).contains("\"code\""));
        // The UI reads input.substitutions[].pathSource/pathTarget, so the schema must describe them.
        String substitutionsSchema = byName.get(MappingResultTools.SUBMIT_SUBSTITUTIONS_TOOL_NAME);
        assertTrue(substitutionsSchema.contains("\"substitutions\""));
        assertTrue(substitutionsSchema.contains("pathSource"));
        assertTrue(substitutionsSchema.contains("pathTarget"));
    }
}
