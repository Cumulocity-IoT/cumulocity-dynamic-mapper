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
 */

package dynamic.mapper.model.status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectorStatusHistory#append}: the history cap / truncation bookkeeping
 * and the sticky {@code hadError} flag — the two pieces of session-bundling state that had no
 * test coverage before (see the connector-log-bundling feature review).
 */
class ConnectorStatusHistoryTest {

    private static ConnectorStatusEvent event(ConnectorStatus status) {
        return new ConnectorStatusEvent(status);
    }

    private static ConnectorStatusEvent eventWithMessage(ConnectorStatus status, String message) {
        ConnectorStatusEvent e = new ConnectorStatusEvent(status);
        e.setMessage(message);
        return e;
    }

    @Test
    void append_setsCurrentStatus_andGrowsHistory() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(event(ConnectorStatus.CONNECTING));
        history.append(event(ConnectorStatus.CONNECTED));

        assertEquals(2, history.getHistory().size());
        assertEquals(ConnectorStatus.CONNECTED, history.getCurrentStatus());
        assertFalse(history.isHistoryTruncated());
        assertEquals(0, history.getHistoryOmittedCount());
    }

    @Test
    void append_belowCap_doesNotTruncate() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        for (int i = 0; i < ConnectorStatusHistory.MAX_HISTORY_ENTRIES; i++) {
            history.append(event(i % 2 == 0 ? ConnectorStatus.CONNECTED : ConnectorStatus.RETRYING));
        }

        assertEquals(ConnectorStatusHistory.MAX_HISTORY_ENTRIES, history.getHistory().size());
        assertFalse(history.isHistoryTruncated());
        assertEquals(0, history.getHistoryOmittedCount());
    }

    @Test
    void append_beyondCap_dropsOldestEntry_andMarksTruncated() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();
        ConnectorStatusEvent first = event(ConnectorStatus.CONNECTED);
        history.append(first);
        for (int i = 1; i < ConnectorStatusHistory.MAX_HISTORY_ENTRIES; i++) {
            history.append(event(i % 2 == 0 ? ConnectorStatus.CONNECTED : ConnectorStatus.RETRYING));
        }
        // one more push over the cap
        history.append(event(ConnectorStatus.RETRYING));

        assertEquals(ConnectorStatusHistory.MAX_HISTORY_ENTRIES, history.getHistory().size(),
                "size stays capped, oldest entry evicted rather than growing unbounded");
        assertTrue(history.isHistoryTruncated());
        assertEquals(1, history.getHistoryOmittedCount());
        assertFalse(history.getHistory().contains(first), "the oldest entry must have been evicted");
    }

    @Test
    void append_multipleOverflows_accumulatesOmittedCount() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        for (int i = 0; i < ConnectorStatusHistory.MAX_HISTORY_ENTRIES + 5; i++) {
            history.append(event(i % 2 == 0 ? ConnectorStatus.CONNECTED : ConnectorStatus.RETRYING));
        }

        assertEquals(ConnectorStatusHistory.MAX_HISTORY_ENTRIES, history.getHistory().size());
        assertTrue(history.isHistoryTruncated());
        assertEquals(5, history.getHistoryOmittedCount());
    }

    @Test
    void append_failedTransition_setsHadError() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(event(ConnectorStatus.CONNECTING));
        assertFalse(history.isHadError());

        history.append(event(ConnectorStatus.FAILED));
        assertTrue(history.isHadError());
    }

    @Test
    void append_retryingTransition_setsHadError() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(event(ConnectorStatus.RETRYING));

        assertTrue(history.isHadError());
    }

    @Test
    void append_nonEmptyMessage_setsHadError_evenOnCleanStatus() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(eventWithMessage(ConnectorStatus.DISCONNECTED, "UnknownHostException: broker.example.com"));

        assertTrue(history.isHadError());
    }

    @Test
    void append_cleanTransitions_leaveHadErrorFalse() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(event(ConnectorStatus.CONNECTING));
        history.append(event(ConnectorStatus.CONNECTED));
        history.append(event(ConnectorStatus.DISCONNECTING));
        history.append(event(ConnectorStatus.DISCONNECTED));

        assertFalse(history.isHadError());
    }

    @Test
    void hadError_isSticky_acrossSubsequentCleanTransitions() {
        ConnectorStatusHistory history = new ConnectorStatusHistory();

        history.append(event(ConnectorStatus.RETRYING));
        assertTrue(history.isHadError());

        history.append(event(ConnectorStatus.CONNECTED));

        assertTrue(history.isHadError(), "hadError must stay true once set, even after a clean transition");
    }
}
