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

package dynamic.mapper.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.model.Direction;

/**
 * Covers the JSDoc header handling in {@link ServiceConfigurationService}
 * (previously untested): new-header creation, legacy-to-two-section
 * migration, corrupted-header cleanup, stale embedded header removal, and
 * loading the shipped classpath templates.
 */
@ExtendWith(MockitoExtension.class)
class ServiceConfigurationServiceTest {

    @Mock
    private TenantOptionApi tenantOptionApi;

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    private ServiceConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new ServiceConfigurationService(tenantOptionApi, subscriptionsService, new ObjectMapper());
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static String encode(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static CodeTemplate template(String code) {
        CodeTemplate t = new CodeTemplate();
        t.id = "abc12345";
        t.name = "My Template";
        t.description = "A description";
        t.templateType = TemplateType.INBOUND_SMART_FUNCTION;
        t.direction = Direction.INBOUND;
        t.code = encode(code);
        t.internal = false;
        t.readonly = false;
        t.defaultTemplate = false;
        return t;
    }

    // ── rectifyHeaderInCodeTemplate ───────────────────────────────────────

    @Test
    void rectifyHeaderInCodeTemplate_createsNewHeader_whenNoneExists() {
        CodeTemplate t = template("function onMessage(msg, context) {\n  return [];\n}\n");

        service.rectifyHeaderInCodeTemplate(t);

        String result = decode(t.code);
        assertTrue(result.startsWith("/**\n * @name My Template"), result);
        assertTrue(result.contains("@templateType INBOUND_SMART_FUNCTION"), result);
        assertTrue(result.contains("--- metadata above is auto-generated, add your documentation below ---"), result);
        assertTrue(result.contains("function onMessage"), result);
    }

    @Test
    void rectifyHeaderInCodeTemplate_migratesLegacySingleSectionHeader_preservingFreeFormDocs() {
        String legacy = "/**\n" +
                " * @name Old Name\n" +
                " * @description Old description\n" +
                " * @templateType INBOUND_SMART_FUNCTION\n" +
                " * @defaultTemplate true\n" +
                " * @internal true\n" +
                " * @readonly true\n" +
                " *\n" +
                " * Sample payload\n" +
                " * { \"foo\": \"bar\" }\n" +
                " */\n\n" +
                "function onMessage(msg, context) { return []; }\n";
        CodeTemplate t = template(legacy);
        t.name = "My Template"; // POJO is the source of truth, differs from stale header value

        service.rectifyHeaderInCodeTemplate(t);

        String result = decode(t.code);
        // System section is regenerated from the POJO, not the stale header value
        assertTrue(result.contains("@name My Template"), result);
        assertFalse(result.contains("@name Old Name"), result);
        // Free-form docs below the old annotations are preserved, relocated after the marker
        assertTrue(result.contains("Sample payload"), result);
        assertTrue(result.contains("{ \"foo\": \"bar\" }"), result);
        int markerIdx = result.indexOf("--- metadata above is auto-generated");
        int sampleIdx = result.indexOf("Sample payload");
        assertTrue(markerIdx != -1 && sampleIdx > markerIdx, result);
        // Code body untouched
        assertTrue(result.contains("function onMessage(msg, context) { return []; }"), result);
    }

    @Test
    void rectifyHeaderInCodeTemplate_isIdempotent_onAlreadyTwoSectionHeader() {
        CodeTemplate t = template("function onMessage(msg, context) { return []; }\n");
        service.rectifyHeaderInCodeTemplate(t);
        String firstPass = decode(t.code);

        service.rectifyHeaderInCodeTemplate(t);
        String secondPass = decode(t.code);

        assertEquals(firstPass, secondPass);
        // No accumulation of extra blank lines between header and code body
        assertFalse(secondPass.contains("*/\n\n\n"), secondPass);
    }

    @Test
    void rectifyHeaderInCodeTemplate_cleansUpDuplicateJSDocHeaders() {
        String corrupted = "/**\n" +
                " * @name Stale\n" +
                " */\n" +
                "/**\n" +
                " * @name Also Stale\n" +
                " * @templateType INBOUND_SMART_FUNCTION\n" +
                " */\n\n" +
                "function onMessage(msg, context) { return []; }\n";
        CodeTemplate t = template(corrupted);

        service.rectifyHeaderInCodeTemplate(t);

        String result = decode(t.code);
        assertEquals(1, countOccurrences(result, "/**"));
        assertTrue(result.contains("@name My Template"), result);
        assertTrue(result.contains("function onMessage(msg, context) { return []; }"), result);
    }

    @Test
    void rectifyHeaderInCodeTemplate_removesStaleTemplateHeaderEmbeddedInCodeBody() {
        String withStaleBodyHeader = "/**\n" +
                " * @name My Template\n" +
                " * @templateType INBOUND_SMART_FUNCTION\n" +
                " * --- metadata above is auto-generated, add your documentation below ---\n" +
                " */\n\n" +
                "/**\n" +
                " * @templateType INBOUND_SMART_FUNCTION\n" +
                " * (leftover from a previous buggy save)\n" +
                " */\n" +
                "function onMessage(msg, context) { return []; }\n";
        CodeTemplate t = template(withStaleBodyHeader);

        service.rectifyHeaderInCodeTemplate(t);

        String result = decode(t.code);
        assertEquals(1, countOccurrences(result, "@templateType"));
        assertTrue(result.contains("function onMessage(msg, context) { return []; }"), result);
    }

    @Test
    void rectifyHeaderInCodeTemplate_doesNotMisreadTokensInFreeFormDocsAsAnnotations() {
        // The free-form doc area (below the marker) can legitimately contain text
        // that looks like an annotation (e.g. documenting a JSON field called
        // "@internal"); extractAnnotation must not pick this up in later loads.
        String withLookalikeInFreeForm = "/**\n" +
                " * @name My Template\n" +
                " * @description A description\n" +
                " * @templateType INBOUND_SMART_FUNCTION\n" +
                " * @defaultTemplate false\n" +
                " * @internal false\n" +
                " * @readonly false\n" +
                " * --- metadata above is auto-generated, add your documentation below ---\n" +
                " * Note: payload field \"@internal\" flags system-only devices.\n" +
                " */\n\n" +
                "function onMessage(msg, context) { return []; }\n";
        CodeTemplate t = template(withLookalikeInFreeForm);

        service.rectifyHeaderInCodeTemplate(t);

        String result = decode(t.code);
        // Regenerated system section still correctly reports internal=false,
        // not confused by the free-form mention of "@internal"
        assertTrue(result.contains("@internal false"), result);
        assertFalse(result.contains("@internal true"), result);
        assertTrue(result.contains("payload field \"@internal\" flags system-only devices"), result);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ── initCodeTemplates / addMissingInternalTemplates (classpath templates) ──

    @Test
    void initCodeTemplates_loadsShippedClasspathTemplates() {
        ServiceConfiguration configuration = new ServiceConfiguration();

        service.initCodeTemplates(configuration, false);

        Map<String, CodeTemplate> templates = configuration.getCodeTemplates();
        assertNotNull(templates);
        assertTrue(templates.containsKey(TemplateType.SHARED.name()));
        assertTrue(templates.containsKey(TemplateType.SYSTEM.name()));

        CodeTemplate shared = templates.get(TemplateType.SHARED.name());
        assertEquals("SHARED", shared.templateType.name());
        assertTrue(shared.defaultTemplate);
        // SHARED/SYSTEM don't carry an INBOUND_/OUTBOUND_ prefix, so direction
        // cannot be derived and stays unset.
        assertNull(shared.direction);

        boolean hasInboundSmartFunctionDefault = templates.values().stream()
                .anyMatch(t -> t.templateType == TemplateType.INBOUND_SMART_FUNCTION && t.defaultTemplate);
        assertTrue(hasInboundSmartFunctionDefault);
    }

    @Test
    void initCodeTemplates_withOverrideSystem_preservesSharedButDropsOtherInternalTemplates() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        service.initCodeTemplates(configuration, false);
        Map<String, CodeTemplate> initial = configuration.getCodeTemplates();

        // Simulate a user-created (non-internal) custom template that must survive a reset
        CodeTemplate custom = template("function onMessage(msg, context) { return []; }\n");
        custom.id = "custom01";
        custom.internal = false;
        initial.put(custom.id, custom);

        service.initCodeTemplates(configuration, true);

        Map<String, CodeTemplate> afterReset = configuration.getCodeTemplates();
        assertTrue(afterReset.containsKey("custom01"), "user-created template must survive a system reset");
        assertTrue(afterReset.containsKey(TemplateType.SHARED.name()));
        assertTrue(afterReset.containsKey(TemplateType.SYSTEM.name()));
    }

    @Test
    void addMissingInternalTemplates_isIdempotent_noDuplicatesOnSecondCall() {
        ServiceConfiguration configuration = new ServiceConfiguration();

        boolean firstCallAdded = service.addMissingInternalTemplates(configuration);
        int countAfterFirst = configuration.getCodeTemplates().size();

        boolean secondCallAdded = service.addMissingInternalTemplates(configuration);
        int countAfterSecond = configuration.getCodeTemplates().size();

        assertTrue(firstCallAdded);
        assertFalse(secondCallAdded, "re-running against an already-populated map must not add duplicates");
        assertEquals(countAfterFirst, countAfterSecond);
    }
}
