/*
 * Copyright (c) 2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.ServiceLogsProbe.Http;
import dynamic.mapper.core.ServiceLogsProbe.Response;
import dynamic.mapper.core.ServiceLogsProbe.Result;
import dynamic.mapper.core.ServiceLogsProbe.Verdict;

class ServiceLogsProbeTest {

    private final ServiceLogsProbe probe = new ServiceLogsProbe(null, null, new ObjectMapper(), "owner");

    private static final String INSTANCE = "dynamic-mapper-service-scope-t1-deployment-abc";

    /** A platform that answers the four calls; the logs call is the variable. */
    private static Http platform(Response logs, String rolesJson, String instancesJson) {
        Map<String, Response> routes = new HashMap<>();
        routes.put("/user/currentUser", new Response(200, rolesJson));
        routes.put("/application/currentApplication", new Response(200, "{\"id\":\"117260\"}"));
        routes.put("/inventory/managedObjects?type=c8y_Application_117260&pageSize=1",
                new Response(200, instancesJson));
        routes.put("/application/applications/117260/logs/" + INSTANCE, logs);
        return (path, accept) -> {
            Response r = routes.get(path);
            if (r == null) {
                throw new AssertionError("unexpected call " + path);
            }
            return r;
        };
    }

    private static final String ROLES_WITHOUT_APPMGMT =
            "{\"effectiveRoles\":[{\"id\":\"ROLE_INVENTORY_READ\"},{\"id\":\"ROLE_EVENT_READ\"}]}";
    private static final String ONE_INSTANCE =
            "{\"managedObjects\":[{\"c8y_Status\":{\"instances\":{\"" + INSTANCE + "\":{}}}}]}";

    @Test
    void serviceUserThatCanReadTheLogsMeansACollectorCanRunInsideTheService() {
        Result r = probe.probe("owner", INSTANCE, platform(
                new Response(200, "{\"dateFrom\":\"a\",\"dateTo\":\"b\",\"logs\":\"l1\\nl2\\n\"}"),
                ROLES_WITHOUT_APPMGMT, ONE_INSTANCE));

        assertEquals(Verdict.CAN_READ_LOGS, r.verdict());
        assertEquals(INSTANCE, r.instance());
        assertTrue(r.steps().stream().anyMatch(s -> s.detail() != null && s.detail().startsWith("read 2 line(s)")));
    }

    @Test
    void forbiddenNamesTheRolesTheServiceUserActuallyHolds() {
        Result r = probe.probe("owner", INSTANCE, platform(
                new Response(403, "{\"error\":\"security/Forbidden\",\"message\":\"Access is denied\"}"),
                ROLES_WITHOUT_APPMGMT, ONE_INSTANCE));

        assertEquals(Verdict.FORBIDDEN, r.verdict());
        assertEquals(List.of("ROLE_INVENTORY_READ", "ROLE_EVENT_READ"), r.effectiveRoles());
        assertTrue(r.applicationManagementRoles().isEmpty());
        assertTrue(r.advice().contains("ROLE_APPLICATION_MANAGEMENT_READ"), r.advice());
        assertTrue(r.steps().stream().anyMatch(s -> "Access is denied".equals(s.detail())));
    }

    @Test
    void anAuthenticationFailureIsNotReportedAsAMissingRole() {
        Result r = probe.probe("owner", INSTANCE, platform(new Response(401, "{\"message\":\"Unauthorized\"}"),
                ROLES_WITHOUT_APPMGMT, ONE_INSTANCE));

        assertEquals(Verdict.INCONCLUSIVE, r.verdict());
        assertTrue(r.advice().contains("401"), r.advice());
    }

    @Test
    void anOwnerThatIsNotSubscribedHasNoServiceUserAndSaysSo() {
        Result r = probe.probe("owner", INSTANCE, null);

        assertEquals(Verdict.OWNER_NOT_SUBSCRIBED, r.verdict());
        assertTrue(r.advice().contains("owner"), r.advice());
    }

    @Test
    void noRunningInstanceIsInconclusiveRatherThanForbidden() {
        Result r = probe.probe("owner", INSTANCE, platform(new Response(200, "{}"), ROLES_WITHOUT_APPMGMT,
                "{\"managedObjects\":[]}"));

        assertEquals(Verdict.INCONCLUSIVE, r.verdict());
    }

    @Test
    void anotherInstanceIsProbedWhenThisOneIsNotListed() {
        // e.g. HOSTNAME is not the platform's instance name; the first listed instance is used instead
        Result r = probe.probe("owner", "some-other-hostname", platform(
                new Response(200, "{\"logs\":\"x\"}"), ROLES_WITHOUT_APPMGMT, ONE_INSTANCE));

        assertEquals(Verdict.CAN_READ_LOGS, r.verdict());
        assertEquals(INSTANCE, r.instance());
        assertTrue(r.steps().stream().anyMatch(s -> s.detail() != null && s.detail().contains("is NOT among")));
    }

    @Test
    void theResultNeverCarriesLogContentOrCredentials() throws Exception {
        Result r = probe.probe("owner", INSTANCE, platform(
                new Response(200, "{\"logs\":\"SECRET-LOG-LINE\"}"), ROLES_WITHOUT_APPMGMT, ONE_INSTANCE));

        assertFalse(new ObjectMapper().writeValueAsString(r).contains("SECRET-LOG-LINE"));
    }
}
