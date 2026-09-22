/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
package dynamic.mapper.connector.httppolling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectionStateManager;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.client.MappingSubscriptionManager;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;

/**
 * Tests for {@link HttpPollingConnector}'s declaration and pure-logic pieces (config validation,
 * connector-specification shape, URL/query-param composition, pagination stop-condition
 * detection) plus a regression test for the {@code ConcurrentHashMap.put(key, null)} bug that
 * previously made {@link HttpPollingConnector#subscribe} throw on every call. Actual HTTP polling
 * requires a live target and is exercised manually against
 * {@code resources/testing/environments/http-polling/} instead (see docs/feature/connector-http-polling.md).
 */
public class HttpPollingConnectorTest {

    private static final Logger TEST_LOG = LoggerFactory.getLogger(HttpPollingConnectorTest.class);

    private HttpPollingConnector client;

    @AfterEach
    public void tearDown() {
        // Every subscribe() test schedules real background work on a real ScheduledExecutorService
        // — shut it down so no daemon thread pool leaks across test runs.
        if (client != null) {
            client.close();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ConnectorConfiguration configWithProperties(Map<String, Object> properties) {
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setIdentifier("test-identifier");
        configuration.setConnectorType(ConnectorType.REST_POLLING);
        configuration.setEnabled(true);
        configuration.setName("REST Polling Test");
        configuration.setProperties(properties);
        return configuration;
    }

    private Map<String, Object> minimalValidProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("url", "https://api.example.com/v1");
        return properties;
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method method = HttpPollingConnector.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /**
     * Builds a client with mocked collaborators injected via reflection (mirroring
     * GooglePubSubClientTest's pattern), so instance methods that read {@code connectorConfiguration}/
     * {@code mappingService}/{@code mappingSubscriptionManager}/{@code connectionStateManager}/
     * {@code serviceConfiguration} can run without the full {@code wireFromRegistry}/
     * {@code initializeManagers} machinery.
     */
    private HttpPollingConnector clientWithMocks(ConnectorConfiguration configuration,
            MappingService mappingService, MappingSubscriptionManager mappingSubscriptionManager,
            ConnectionStateManager connectionStateManager,
            ServiceConfiguration serviceConfiguration, CamelDispatcherInbound dispatcher) throws Exception {
        HttpPollingConnector c = new HttpPollingConnector();
        setField(c, "connectorConfiguration", configuration);
        setField(c, "mappingService", mappingService);
        setField(c, "mappingSubscriptionManager", mappingSubscriptionManager);
        setField(c, "connectionStateManager", connectionStateManager);
        setField(c, "serviceConfiguration", serviceConfiguration);
        setField(c, "dispatcher", dispatcher);
        setField(c, "tenant", "test-tenant");
        setField(c, "connectorName", "Test REST Polling");
        setField(c, "connectorIdentifier", "test-id");
        setField(c, "baseUrl", (String) configuration.getProperties().get("url"));
        return c;
    }

    // -------------------------------------------------------------------------
    // isConfigValid
    // -------------------------------------------------------------------------

    @Test
    public void testIsConfigValid_nullConfig_returnsFalse() {
        client = new HttpPollingConnector();
        assertFalse(client.isConfigValid(null));
    }

    @Test
    public void testIsConfigValid_missingUrl_returnsFalse() {
        client = new HttpPollingConnector();
        assertFalse(client.isConfigValid(configWithProperties(new HashMap<>())));
    }

    @Test
    public void testIsConfigValid_urlOnly_returnsTrue() {
        client = new HttpPollingConnector();
        assertTrue(client.isConfigValid(configWithProperties(minimalValidProperties())));
    }

    @Test
    public void testIsConfigValid_pollIntervalBelowFloor_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("pollIntervalSeconds", 10);
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_pollIntervalAtFloor_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("pollIntervalSeconds", 30);
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_basicAuthMissingCredentials_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("authentication", "Basic");
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_basicAuthWithCredentials_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("authentication", "Basic");
        properties.put("user", "poller");
        properties.put("password", "secret");
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_bearerAuthMissingToken_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("authentication", "Bearer");
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_bearerAuthWithToken_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("authentication", "Bearer");
        properties.put("token", "test-token");
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationNextFieldInBody_missingPageParam_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "NextFieldInBody");
        properties.put("nextPageExpression", "nextPageToken");
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationNextFieldInBody_missingNextPageExpression_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "NextFieldInBody");
        properties.put("pageParam", "page");
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationNextFieldInBody_complete_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "NextFieldInBody");
        properties.put("pageParam", "page");
        properties.put("nextPageExpression", "nextPageToken");
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationPageNumber_missingPageParam_returnsFalse() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "PageNumber");
        assertFalse(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationPageNumber_withPageParam_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "PageNumber");
        properties.put("pageParam", "page");
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testIsConfigValid_paginationNextLinkHeader_noPageParamNeeded_returnsTrue() {
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("paginationMode", "NextLinkHeader");
        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    // -------------------------------------------------------------------------
    // Connector specification
    // -------------------------------------------------------------------------

    @Test
    public void testConnectorSpecification_inboundOnly() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        assertEquals(ConnectorType.REST_POLLING, spec.getConnectorType());
        assertEquals(1, spec.getSupportedDirections().size());
        assertTrue(spec.getSupportedDirections().contains(Direction.INBOUND));
    }

    @Test
    public void testConnectorSpecification_urlRequired() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        assertTrue(spec.getProperties().get("url").getRequired());
    }

    @Test
    public void testConnectorSpecification_pollIntervalDefault60() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        ConnectorProperty pollInterval = spec.getProperties().get("pollIntervalSeconds");
        assertFalse(pollInterval.getRequired());
        assertEquals(60L, pollInterval.getDefaultValue());
    }

    @Test
    public void testConnectorSpecification_outboundWildcardHiddenNotJustDisabled() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        // Regression: this connector is inbound-only, so the outbound wildcard flag must be
        // hidden entirely from the form, not merely shown disabled — see the "hide over disable"
        // fix in connector-http.md/connector-webhook.md/connector-http-polling.md.
        ConnectorProperty outboundWildcard = spec.getProperties().get("supportsWildcardInTopicOutbound");
        assertTrue(outboundWildcard.getReadonly());
        assertTrue(outboundWildcard.getHidden());
    }

    @Test
    public void testConnectorSpecification_inboundWildcardShown() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        ConnectorProperty inboundWildcard = spec.getProperties().get("supportsWildcardInTopicInbound");
        assertTrue(inboundWildcard.getReadonly());
        assertFalse(Boolean.TRUE.equals(inboundWildcard.getHidden()));
    }

    @Test
    public void testConnectorSpecification_pageParamConditionalOnPaginationMode() {
        client = new HttpPollingConnector();
        ConnectorSpecification spec = client.getConnectorSpecification();

        ConnectorProperty pageParam = spec.getProperties().get("pageParam");
        assertNotNull(pageParam.getCondition());
        assertEquals("paginationMode", pageParam.getCondition().getKey());
        assertEquals(Set.of("NextFieldInBody", "PageNumber"), Set.of(pageParam.getCondition().getAnyOf()));
    }

    // -------------------------------------------------------------------------
    // supportsWildcardInTopic
    // -------------------------------------------------------------------------

    @Test
    public void testSupportsWildcardInTopic_defaultsFalseBothDirections() throws Exception {
        client = new HttpPollingConnector();
        setField(client, "connectorConfiguration", configWithProperties(new HashMap<>()));

        assertFalse(client.supportsWildcardInTopic(Direction.INBOUND));
        assertFalse(client.supportsWildcardInTopic(Direction.OUTBOUND));
    }

    @Test
    public void testSupportsWildcardInTopic_notSwayedByStoredConfigOverride() throws Exception {
        // Regression: supportsWildcardInTopic() must not delegate to readWildcardFlag() (which
        // reads connectorConfiguration's stored properties) for this connector — there is no
        // subscription-pattern-matching mechanism it could honor even if a raw API call/import
        // bypassed the UI's readonly hint and stored supportsWildcardInTopicInbound=true.
        client = new HttpPollingConnector();
        Map<String, Object> properties = minimalValidProperties();
        properties.put("supportsWildcardInTopicInbound", true);
        properties.put("supportsWildcardInTopicOutbound", true);
        setField(client, "connectorConfiguration", configWithProperties(properties));

        assertFalse(client.supportsWildcardInTopic(Direction.INBOUND));
        assertFalse(client.supportsWildcardInTopic(Direction.OUTBOUND));
    }

    // -------------------------------------------------------------------------
    // HttpPollingRequestHelper.topicPath — pure, called directly (package-private, no reflection
    // needed now that the pure request/response helpers live outside HttpPollingConnector)
    // -------------------------------------------------------------------------

    @Test
    public void testTopicPath_addsLeadingSlashWhenMissing() {
        assertEquals("/devices/measurements", HttpPollingRequestHelper.topicPath("devices/measurements"));
    }

    @Test
    public void testTopicPath_preservesExistingLeadingSlash() {
        assertEquals("/devices/measurements", HttpPollingRequestHelper.topicPath("/devices/measurements"));
    }

    // -------------------------------------------------------------------------
    // HttpPollingRequestHelper.buildQueryParams
    // -------------------------------------------------------------------------

    @Test
    public void testBuildQueryParams_cursorOnly_noneMode() {
        Map<String, String> params = HttpPollingRequestHelper.buildQueryParams(
                "2026-01-01T00:00:00Z", "since", "None", null, null);

        assertEquals(Map.of("since", "2026-01-01T00:00:00Z"), params);
    }

    @Test
    public void testBuildQueryParams_pageNumberMode_includesPageParam() {
        Map<String, String> params = HttpPollingRequestHelper.buildQueryParams(
                null, null, "PageNumber", "page", "3");

        assertEquals(Map.of("page", "3"), params);
    }

    @Test
    public void testBuildQueryParams_cursorAndPageCombineFreely() {
        Map<String, String> params = HttpPollingRequestHelper.buildQueryParams(
                "cursor-1", "since", "NextFieldInBody", "pageToken", "tok-2");

        assertEquals(Map.of("since", "cursor-1", "pageToken", "tok-2"), params);
    }

    @Test
    public void testBuildQueryParams_nextLinkHeaderMode_contributesNoPageParam() {
        // NextLinkHeader's continuation is an absolute URI handled elsewhere — buildQueryParams
        // must not add a page param for it even if a stray value is passed in.
        Map<String, String> params = HttpPollingRequestHelper.buildQueryParams(
                "cursor-1", "since", "NextLinkHeader", "page", "ignored");

        assertEquals(Map.of("since", "cursor-1"), params);
    }

    // -------------------------------------------------------------------------
    // HttpPollingRequestHelper.isEmptyPage — PageNumber mode's stop condition
    // -------------------------------------------------------------------------

    @Test
    public void testIsEmptyPage_blankBody_true() {
        assertTrue(HttpPollingRequestHelper.isEmptyPage(null));
        assertTrue(HttpPollingRequestHelper.isEmptyPage("   "));
    }

    @Test
    public void testIsEmptyPage_emptyArray_true() {
        assertTrue(HttpPollingRequestHelper.isEmptyPage("[]"));
    }

    @Test
    public void testIsEmptyPage_emptyObject_true() {
        assertTrue(HttpPollingRequestHelper.isEmptyPage("{}"));
    }

    @Test
    public void testIsEmptyPage_nonEmptyArray_false() {
        assertFalse(HttpPollingRequestHelper.isEmptyPage("[{\"id\":1}]"));
    }

    @Test
    public void testIsEmptyPage_nonEmptyObject_false() {
        assertFalse(HttpPollingRequestHelper.isEmptyPage("{\"id\":1}"));
    }

    @Test
    public void testIsEmptyPage_unparsableBody_treatedAsNonEmpty() {
        // Defensive: don't guess on a parse failure — treat as non-empty so pagination halts on
        // maxPagesPerPoll rather than silently stopping early on a transient parse issue.
        assertFalse(HttpPollingRequestHelper.isEmptyPage("not json"));
    }

    // -------------------------------------------------------------------------
    // HttpPollingRequestHelper.extractNextLinkUri — NextLinkHeader mode's continuation
    // -------------------------------------------------------------------------

    @Test
    public void testExtractNextLinkUri_present() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK, "<https://api.example.com/x?page=2>; rel=\"next\"");
        ResponseEntity<String> response = new ResponseEntity<>("{}", headers, 200);

        URI result = HttpPollingRequestHelper.extractNextLinkUri(response, "test-tenant", TEST_LOG);

        assertEquals(URI.create("https://api.example.com/x?page=2"), result);
    }

    @Test
    public void testExtractNextLinkUri_picksNextAmongMultipleRelations() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK, "<https://api.example.com/x?page=1>; rel=\"prev\", "
                + "<https://api.example.com/x?page=3>; rel=\"next\", "
                + "<https://api.example.com/x?page=9>; rel=\"last\"");
        ResponseEntity<String> response = new ResponseEntity<>("{}", headers, 200);

        URI result = HttpPollingRequestHelper.extractNextLinkUri(response, "test-tenant", TEST_LOG);

        assertEquals(URI.create("https://api.example.com/x?page=3"), result);
    }

    @Test
    public void testExtractNextLinkUri_absentHeader_returnsNull() {
        ResponseEntity<String> response = new ResponseEntity<>("{}", new HttpHeaders(), 200);

        assertNull(HttpPollingRequestHelper.extractNextLinkUri(response, "test-tenant", TEST_LOG));
    }

    @Test
    public void testExtractNextLinkUri_noNextRelation_returnsNull() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK, "<https://api.example.com/x?page=1>; rel=\"prev\"");
        ResponseEntity<String> response = new ResponseEntity<>("{}", headers, 200);

        assertNull(HttpPollingRequestHelper.extractNextLinkUri(response, "test-tenant", TEST_LOG));
    }

    // -------------------------------------------------------------------------
    // HttpPollingRequestHelper.isPaginationRuntimeConfigValid — runtime counterpart of
    // isConfigValid's pagination checks
    // -------------------------------------------------------------------------

    @Test
    public void testIsPaginationRuntimeConfigValid_none_true() {
        assertTrue(HttpPollingRequestHelper.isPaginationRuntimeConfigValid("None", null, null));
    }

    @Test
    public void testIsPaginationRuntimeConfigValid_nextLinkHeader_true() {
        assertTrue(HttpPollingRequestHelper.isPaginationRuntimeConfigValid("NextLinkHeader", null, null));
    }

    @Test
    public void testIsPaginationRuntimeConfigValid_pageNumberMissingPageParam_false() {
        assertFalse(HttpPollingRequestHelper.isPaginationRuntimeConfigValid("PageNumber", null, null));
    }

    @Test
    public void testIsPaginationRuntimeConfigValid_pageNumberWithPageParam_true() {
        assertTrue(HttpPollingRequestHelper.isPaginationRuntimeConfigValid("PageNumber", "page", null));
    }

    @Test
    public void testIsPaginationRuntimeConfigValid_nextFieldInBodyMissingExpression_false() {
        assertFalse(HttpPollingRequestHelper.isPaginationRuntimeConfigValid("NextFieldInBody", "page", null));
    }

    // -------------------------------------------------------------------------
    // subscribe/unsubscribe — regression test for the ConcurrentHashMap.put(key, null) NPE bug
    // -------------------------------------------------------------------------

    @Test
    public void testSubscribeThenUnsubscribe_doesNotThrow() throws Exception {
        MappingService mappingService = mock(MappingService.class);
        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound()).thenReturn(Collections.emptyMap());
        ConnectionStateManager connectionStateManager = mock(ConnectionStateManager.class);
        ServiceConfiguration serviceConfiguration = mock(ServiceConfiguration.class);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        CamelDispatcherInbound dispatcher = mock(CamelDispatcherInbound.class);

        Map<String, Object> properties = minimalValidProperties();
        properties.put("pollIntervalSeconds", 30);
        client = clientWithMocks(configWithProperties(properties), mappingService, mappingSubscriptionManager,
                connectionStateManager, serviceConfiguration, dispatcher);

        String topic = "devices/measurements";

        // Regression: subscribe() used to throw NullPointerException synchronously here —
        // ConcurrentHashMap.put(topic, null) — before scheduling ever happened, breaking every
        // real mapping subscribe and every Message Explorer session on this connector.
        assertDoesNotThrow(() -> client.subscribe(topic, Qos.AT_LEAST_ONCE));

        Set<String> subscribedTopics = getField(client, "subscribedTopics");
        assertTrue(subscribedTopics.contains(topic));

        assertDoesNotThrow(() -> client.unsubscribe(topic));
        assertFalse(subscribedTopics.contains(topic));
    }

    @Test
    public void testSubscribeTwice_replacesPreviousJobForSameTopic() throws Exception {
        MappingService mappingService = mock(MappingService.class);
        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound()).thenReturn(Collections.emptyMap());
        ConnectionStateManager connectionStateManager = mock(ConnectionStateManager.class);
        ServiceConfiguration serviceConfiguration = mock(ServiceConfiguration.class);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        CamelDispatcherInbound dispatcher = mock(CamelDispatcherInbound.class);

        Map<String, Object> properties = minimalValidProperties();
        properties.put("pollIntervalSeconds", 30);
        client = clientWithMocks(configWithProperties(properties), mappingService, mappingSubscriptionManager,
                connectionStateManager, serviceConfiguration, dispatcher);

        String topic = "devices/measurements";

        assertDoesNotThrow(() -> client.subscribe(topic, Qos.AT_LEAST_ONCE));
        assertDoesNotThrow(() -> client.subscribe(topic, Qos.AT_LEAST_ONCE));

        Set<String> subscribedTopics = getField(client, "subscribedTopics");
        assertEquals(1, subscribedTopics.size());
        assertTrue(subscribedTopics.contains(topic));
    }

    // -------------------------------------------------------------------------
    // resolveMapping / currentCursor (private) — incremental fetch (v2) topic-to-mapping lookup
    // -------------------------------------------------------------------------

    @Test
    public void testCurrentCursor_noMappingForTopic_returnsNull() throws Exception {
        client = new HttpPollingConnector();
        setField(client, "tenant", "test-tenant");

        Object result = invokePrivate(client, "currentCursor", new Class<?>[] { Mapping.class }, (Object) null);
        assertNull(result);
    }

    @Test
    public void testCurrentCursor_resolvesMappingByTopicAndReadsStoredCursor() throws Exception {
        Mapping mapping = Mapping.builder()
                .identifier("mapping-1")
                .mappingTopic("devices/measurements")
                .direction(Direction.INBOUND)
                .build();
        MappingStatus status = new MappingStatus();
        status.setCursor("2026-01-01T00:00:00Z");

        MappingService mappingService = mock(MappingService.class);
        when(mappingService.getMappingStatus("test-tenant", mapping)).thenReturn(status);
        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound())
                .thenReturn(Map.of("mapping-1", mapping));

        client = new HttpPollingConnector();
        setField(client, "mappingService", mappingService);
        setField(client, "mappingSubscriptionManager", mappingSubscriptionManager);
        setField(client, "tenant", "test-tenant");

        Mapping resolved = (Mapping) invokePrivate(client, "resolveMapping",
                new Class<?>[] { String.class }, "devices/measurements");
        assertEquals("mapping-1", resolved.getIdentifier());

        Object cursor = invokePrivate(client, "currentCursor", new Class<?>[] { Mapping.class }, resolved);
        assertEquals("2026-01-01T00:00:00Z", cursor);
    }

    @Test
    public void testResolveMapping_multipleMappingsShareTopic_picksLowestIdentifierDeterministically()
            throws Exception {
        // Regression: getEffectiveMappingsInbound() is a plain ConcurrentHashMap with no
        // iteration-order guarantee. resolveMapping() must not depend on encounter order (e.g. a
        // stream findFirst()) when several mappings share a topic, or the mapping whose
        // MappingStatus.cursor is read/written could silently change across restarts or after
        // unrelated map churn triggers a rehash — see the Javadoc on resolveMapping().
        Mapping mappingB = Mapping.builder()
                .identifier("mapping-b")
                .mappingTopic("devices/measurements")
                .direction(Direction.INBOUND)
                .build();
        Mapping mappingA = Mapping.builder()
                .identifier("mapping-a")
                .mappingTopic("devices/measurements")
                .direction(Direction.INBOUND)
                .build();
        Mapping mappingC = Mapping.builder()
                .identifier("mapping-c")
                .mappingTopic("devices/measurements")
                .direction(Direction.INBOUND)
                .build();

        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        // Insertion order deliberately not sorted, to prove the result doesn't depend on it.
        Map<String, Mapping> effective = new HashMap<>();
        effective.put("mapping-b", mappingB);
        effective.put("mapping-a", mappingA);
        effective.put("mapping-c", mappingC);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound()).thenReturn(effective);

        client = new HttpPollingConnector();
        setField(client, "mappingSubscriptionManager", mappingSubscriptionManager);
        setField(client, "tenant", "test-tenant");

        Mapping resolved = (Mapping) invokePrivate(client, "resolveMapping",
                new Class<?>[] { String.class }, "devices/measurements");

        assertEquals("mapping-a", resolved.getIdentifier());
    }

    // -------------------------------------------------------------------------
    // awaitProcessingSuccess (private) — deep-review round 2026-09-22:
    // ProcessingResultHelper.failure() builds a wrapper with no Future at all
    // -------------------------------------------------------------------------

    @Test
    public void testAwaitProcessingSuccess_nullProcessingResult_returnsFalseWithoutNPE() throws Exception {
        // Regression: ProcessingResultHelper.failure() sets no processingResult, so
        // resultWrapper.getProcessingResult() is null. Before the fix, calling .get() on that
        // unconditionally threw NullPointerException (accidentally caught two frames up in
        // executePoll's generic catch, not handled deliberately).
        client = new HttpPollingConnector();
        setField(client, "tenant", "test-tenant");

        ProcessingResultWrapper<Object> failure = ProcessingResultHelper.failure();

        Object result = assertDoesNotThrow(() -> invokePrivate(client, "awaitProcessingSuccess",
                new Class<?>[] { ProcessingResultWrapper.class, String.class }, failure, "devices/measurements"));
        assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void testAwaitProcessingSuccess_completedFutureNoErrors_returnsTrue() throws Exception {
        client = new HttpPollingConnector();
        setField(client, "tenant", "test-tenant");

        List<ProcessingContext<Object>> emptyResults = Collections.emptyList();
        ProcessingResultWrapper<Object> success = ProcessingResultWrapper.<Object>builder()
                .processingResult(CompletableFuture.completedFuture(emptyResults))
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .build();

        Object result = invokePrivate(client, "awaitProcessingSuccess",
                new Class<?>[] { ProcessingResultWrapper.class, String.class }, success, "devices/measurements");
        assertEquals(Boolean.TRUE, result);
    }

    // -------------------------------------------------------------------------
    // executePoll (private) — deep-review round 2026-09-22: concurrent-execution guard
    // -------------------------------------------------------------------------

    @Test
    public void testExecutePoll_skipsWhenPreviousPollStillInFlight() throws Exception {
        // Regression: upgradeQosForRetainedTopics() re-invokes subscribe() for an
        // already-subscribed topic, which used to unconditionally cancel+reschedule — allowing a
        // second executePoll() to start for the same topic while the first was still blocked on
        // its HTTP call, racing on the same mapping's cursor. Simulate "already in flight" and
        // confirm the second invocation returns immediately instead of touching the (here, null)
        // pollingClient — an NPE from pollingClient would prove the guard did NOT trigger.
        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound()).thenReturn(Collections.emptyMap());

        client = new HttpPollingConnector();
        setField(client, "tenant", "test-tenant");
        setField(client, "mappingSubscriptionManager", mappingSubscriptionManager);
        setField(client, "connectorConfiguration", configWithProperties(minimalValidProperties()));

        String topic = "devices/measurements";
        Set<String> subscribedTopics = getField(client, "subscribedTopics");
        subscribedTopics.add(topic);
        Set<String> inFlightTopics = getField(client, "inFlightTopics");
        inFlightTopics.add(topic);

        assertDoesNotThrow(() -> invokePrivate(client, "executePoll", new Class<?>[] { String.class }, topic));

        // The guard must not have cleared the in-flight marker of the poll it deferred to.
        assertTrue(inFlightTopics.contains(topic));
    }

    // -------------------------------------------------------------------------
    // handlePollFailure (private) — deep-review round 2026-09-22: per-topic failure isolation
    // -------------------------------------------------------------------------

    @Test
    public void testHandlePollFailure_isolatedPerTopic() throws Exception {
        // Regression: a single connector-wide consecutiveFailures counter meant a persistently
        // broken topic could push a healthy, unrelated topic on the same connector past
        // MAX_CONSECUTIVE_FAILURES and mark the whole connector FAILED (terminal) on the
        // healthy topic's behalf. Each topic must accumulate its own failure streak.
        MappingSubscriptionManager mappingSubscriptionManager = mock(MappingSubscriptionManager.class);
        when(mappingSubscriptionManager.getEffectiveMappingsInbound()).thenReturn(Collections.emptyMap());
        ConnectionStateManager connectionStateManager = mock(ConnectionStateManager.class);

        client = new HttpPollingConnector();
        setField(client, "tenant", "test-tenant");
        setField(client, "mappingSubscriptionManager", mappingSubscriptionManager);
        setField(client, "connectionStateManager", connectionStateManager);
        setField(client, "connectorConfiguration", configWithProperties(minimalValidProperties()));

        String brokenTopic = "devices/broken";
        String healthyTopic = "devices/healthy";
        Set<String> subscribedTopics = getField(client, "subscribedTopics");
        subscribedTopics.add(brokenTopic);
        subscribedTopics.add(healthyTopic);

        Exception failure = new RuntimeException("simulated poll failure");
        for (int i = 0; i < 5; i++) {
            invokePrivate(client, "handlePollFailure", new Class<?>[] { String.class, Exception.class },
                    brokenTopic, failure);
        }
        invokePrivate(client, "handlePollFailure", new Class<?>[] { String.class, Exception.class },
                healthyTopic, failure);

        Map<String, AtomicInteger> consecutiveFailuresByTopic = getField(client, "consecutiveFailuresByTopic");
        assertEquals(5, consecutiveFailuresByTopic.get(brokenTopic).get());
        assertEquals(1, consecutiveFailuresByTopic.get(healthyTopic).get());
    }
}
