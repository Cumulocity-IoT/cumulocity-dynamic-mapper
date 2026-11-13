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

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.inbound.InboundMessageRouter;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.inbound.processor.DeserializationInboundProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.InternalProtobufProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionResultInboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.util.ConsolidationProcessor;

/**
 * Message de-duplication through the real inbound pipeline (issue #692).
 *
 * <p>{@link InboundMessageRouter} runs with the real deserialization, enrichment, filter, JSONata extraction,
 * substitution, and consolidation processors, a real {@link MessageDeduplicationService}, and a real
 * {@link MappingStatus} per mapping. Only the send to Cumulocity is replaced: the test counts the sends, and can
 * make one fail, as a rejected request would.</p>
 *
 * <p>Unit tests cover the store and the filter function alone. This test checks what they cannot: that a repeat
 * never reaches the send step, that the statistics add up, that a failed message does not block its retry, and
 * that concurrent copies of one message send once.</p>
 */
class MessageDeduplicationIntegrationTest {

    private static final String TENANT = "testTenant";
    private static final String CONNECTOR = "mqtt";
    private static final String TOPIC = "/plant1/line1/device1_measure1_Type";
    private static final String FIXTURE = "dynamic-mapper-service/src/test/resources/mappings-fixture-INBOUND.json";

    /** Same device and time means same message. A message without time is filtered by the first condition. */
    private static final String FILTER = "$exists(time) and $not($isDuplicate(_TOPIC_LEVEL_[1] & \"|\" & time, 60))";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InboundMessageRouter router;
    private ExecutorService virtualThreadPool;
    private final Map<String, MappingStatus> statuses = new HashMap<>();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicBoolean failSends = new AtomicBoolean();
    private ServiceConfiguration serviceConfiguration;

    @BeforeEach
    void setUp() throws Exception {
        virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();
        serviceConfiguration = mock(ServiceConfiguration.class);

        // The device of the external ID exists, so the substitution needs no identity service
        ManagedObjectRepresentation device = new ManagedObjectRepresentation();
        device.setId(new GId("12345"));
        ExternalIDRepresentation externalId = new ExternalIDRepresentation();
        externalId.setManagedObject(device);
        C8YAgent c8yAgent = mock(C8YAgent.class);
        when(c8yAgent.resolveExternalId2GlobalId(eq(TENANT), any(), anyBoolean())).thenReturn(externalId);

        MessageDeduplicationService store = new MessageDeduplicationService();
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getMessageDeduplicationService()).thenReturn(store);
        when(serviceRegistry.getVirtualThreadPool()).thenReturn(virtualThreadPool);

        MappingService mappingService = mock(MappingService.class);
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenAnswer(call -> {
            Mapping m = call.getArgument(1);
            synchronized (statuses) {
                return statuses.computeIfAbsent(m.getIdentifier(), id -> new MappingStatus(m.getId(), m.getName(),
                        id, Direction.INBOUND, m.getMappingTopic(), "", 0L, 0L, 0L, null));
            }
        });

        AConnectorClient connector = mock(AConnectorClient.class);
        when(connector.isMappingInboundDeployed(any())).thenReturn(true);
        ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
        when(connectorRegistry.getClientForTenant(TENANT, CONNECTOR)).thenReturn(connector);

        // The only replaced step: count the send, and fail it on request, as a rejected request would
        SendInboundProcessor send = mock(SendInboundProcessor.class);
        doAnswer(call -> {
            ProcessingContext<?> context = call.getArgument(0);
            if (failSends.get()) {
                context.addError(new ProcessingException("Cumulocity rejected the request"));
            } else {
                sends.incrementAndGet();
            }
            return null;
        }).when(send).process(any());

        router = new InboundMessageRouter(
                connectorRegistry,
                virtualThreadPool,
                mock(ExtensibleInboundProcessor.class),
                mock(ExtensibleResultInboundProcessor.class),
                mock(InternalProtobufProcessor.class),
                new EnrichmentInboundProcessor(serviceRegistry, mappingService, mock(FlowStateStore.class)),
                mock(FlowInboundProcessor.class),
                new SubstitutionResultInboundProcessor(c8yAgent, mappingService, null, null),
                new DeserializationInboundProcessor(mappingService, null),
                new JSONataInboundProcessor(mappingService),
                mock(FlowResultInboundProcessor.class),
                new FilterInboundProcessor(serviceRegistry, store),
                send,
                new ConsolidationProcessor(mappingService));
    }

    @AfterEach
    void tearDown() {
        virtualThreadPool.shutdownNow();
    }

    // ========== helpers ==========

    /** A fresh copy of the fixture mapping "Mapping - 01" with the de-duplication filter. */
    private Mapping mapping(String identifier) throws Exception {
        Path root = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent().getParent();
        List<Mapping> all = objectMapper.readValue(Files.readString(root.resolve(FIXTURE)),
                new TypeReference<List<Mapping>>() {
                });
        Mapping m = all.stream().filter(x -> "Mapping - 01".equals(x.getName())).findFirst().orElseThrow();
        m.setIdentifier(identifier);
        m.setActive(true);
        m.setFilterMapping(FILTER);
        return m;
    }

    private ConnectorMessage message(String json, boolean testRun) {
        return ConnectorMessage.builder()
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .tenant(TENANT)
                .topic(TOPIC)
                .clientId("client-1")
                .connectorIdentifier(CONNECTOR)
                .sendPayload(false)
                .testRun(testRun)
                .build();
    }

    private static String payload(String time) {
        return "{\"value\":100,\"time\":\"" + time + "\"}";
    }

    private List<ProcessingContext<Object>> deliver(String json, Mapping... mappings) {
        return router.processInboundMessage(message(json, false), List.of(mappings), false, serviceConfiguration, null);
    }

    private MappingStatus statusOf(Mapping m) {
        synchronized (statuses) {
            return statuses.get(m.getIdentifier());
        }
    }

    // ========== tests ==========

    @Test
    void aRepeatedMessageIsNotSentAndCountsAsRepeated() throws Exception {
        Mapping m = mapping("dedup-a");

        var first = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertFalse(first.isIgnoreFurtherProcessing());
        assertEquals(1, sends.get(), "the first copy is sent");

        var repeat = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertTrue(repeat.isIgnoreFurtherProcessing());
        assertTrue(repeat.isDroppedAsDuplicate(), "the context says why the message was dropped");
        assertEquals(1, sends.get(), "the repeat never reaches the send step");

        MappingStatus status = statusOf(m);
        assertNotNull(status);
        assertEquals(1, status.messagesProcessed);
        assertEquals(1, status.messagesFiltered, "a repeat is a filtered message");
        assertEquals(1, status.messagesDuplicate, "and is counted as repeated");
        assertEquals(0, status.errors);
    }

    @Test
    void aMessageWithAnotherKeyIsNotARepeat() throws Exception {
        Mapping m = mapping("dedup-b");
        deliver(payload("2026-11-12T10:00:00Z"), m);
        var other = deliver(payload("2026-11-12T10:00:01Z"), m).get(0);
        assertFalse(other.isIgnoreFurtherProcessing());
        assertEquals(2, sends.get());
        assertEquals(0, statusOf(m).messagesDuplicate);
    }

    @Test
    void aMessageRejectedByTheFirstFilterConditionIsFilteredButNotRepeated() throws Exception {
        Mapping m = mapping("dedup-c");
        // no time: the $exists guard rejects it, and nothing is recorded
        for (int i = 0; i < 2; i++) {
            var ctx = deliver("{\"value\":100}", m).get(0);
            assertTrue(ctx.isIgnoreFurtherProcessing());
            assertFalse(ctx.isDroppedAsDuplicate());
        }
        assertEquals(0, sends.get());
        assertEquals(2, statusOf(m).messagesFiltered);
        assertEquals(0, statusOf(m).messagesDuplicate);
    }

    @Test
    void theRetryOfAFailedMessageIsProcessedAndItsRepeatAfterSuccessIsDropped() throws Exception {
        Mapping m = mapping("dedup-d");

        failSends.set(true);
        var failed = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertTrue(failed.hasError(), "the send was rejected");
        assertEquals(0, sends.get());

        failSends.set(false);
        var retry = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertFalse(retry.isIgnoreFurtherProcessing(), "the broker's retry of a failed message is processed");
        assertEquals(1, sends.get());

        var third = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertTrue(third.isDroppedAsDuplicate(), "once delivered, a further copy is a repeat");
        assertEquals(1, sends.get());

        MappingStatus status = statusOf(m);
        assertEquals(1, status.messagesProcessed);
        assertEquals(1, status.messagesDuplicate);
    }

    @Test
    void mappingsOnTheSameTopicKeepTheirOwnKeys() throws Exception {
        Mapping a = mapping("dedup-e1");
        Mapping b = mapping("dedup-e2");
        b.setFilterMapping("$exists(time)"); // no de-duplication in this one

        deliver(payload("2026-11-12T10:00:00Z"), a, b);
        assertEquals(2, sends.get(), "both mappings process the first copy");

        deliver(payload("2026-11-12T10:00:00Z"), a, b);
        assertEquals(3, sends.get(), "only the mapping without the check processes the repeat");
        assertEquals(1, statusOf(a).messagesDuplicate);
        assertEquals(0, statusOf(b).messagesDuplicate);
        assertEquals(2, statusOf(b).messagesProcessed);
    }

    @Test
    void aTestRunNeverRecordsAKey() throws Exception {
        Mapping m = mapping("dedup-f");
        for (int i = 0; i < 2; i++) {
            var ctx = router.processInboundMessage(message(payload("2026-11-12T10:00:00Z"), true), List.of(m),
                    false, serviceConfiguration, null).get(0);
            assertFalse(ctx.isDroppedAsDuplicate(), "a test run is never a repeat");
        }
        var real = deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
        assertFalse(real.isIgnoreFurtherProcessing(), "the test runs left no key behind");
    }

    @Test
    void concurrentCopiesOfOneMessageAreSentOnce() throws Exception {
        Mapping m = mapping("dedup-g");
        int copies = 32;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProcessingContext<Object>>> results = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return deliver(payload("2026-11-12T10:00:00Z"), m).get(0);
            }));
        }
        start.countDown();
        int dropped = 0;
        for (var f : results) {
            if (f.get(30, TimeUnit.SECONDS).isDroppedAsDuplicate()) {
                dropped++;
            }
        }
        pool.shutdown();

        assertEquals(1, sends.get(), "exactly one of the concurrent copies is sent");
        assertEquals(copies - 1, dropped);
        MappingStatus status = statusOf(m);
        assertEquals(1, status.messagesProcessed);
        assertEquals(copies - 1, status.messagesDuplicate);
    }
}
