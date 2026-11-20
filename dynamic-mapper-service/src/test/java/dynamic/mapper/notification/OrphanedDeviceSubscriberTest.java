package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class OrphanedDeviceSubscriberTest {

    private static final Set<String> IDS = Set.of("conn1", "conn2");

    @Test
    void ownSubscriberOfACurrentConnectorIsValid() {
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("conn1", IDS, ""));
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("conn1local", IDS, "local"));
    }

    @Test
    void subscriberOfADeletedConnectorIsAnOrphan() {
        assertTrue(NotificationSubscriber.isOrphanedDeviceSubscriber("gone", IDS, ""));
        assertTrue(NotificationSubscriber.isOrphanedDeviceSubscriber("gonelocal", IDS, "local"));
    }

    @Test
    void instanceWithASuffixLeavesUnsuffixedAndForeignSuffixedNamesAlone() {
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("conn1", IDS, "local"));
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("conn1other", IDS, "local"));
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("gone", IDS, "local"));
    }

    @Test
    void instanceWithoutASuffixLeavesSuffixedNamesOfCurrentConnectorsAlone() {
        assertFalse(NotificationSubscriber.isOrphanedDeviceSubscriber("conn1local", IDS, ""));
    }
}
