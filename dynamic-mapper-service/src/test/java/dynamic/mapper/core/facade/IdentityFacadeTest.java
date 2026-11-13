package dynamic.mapper.core.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.sdk.client.identity.IdentityApi;

import dynamic.mapper.core.mock.MockIdentity;

/** An interrupted acquire must not release a permit it never held, and must keep the interrupt flag. */
class IdentityFacadeTest {

    private final IdentityApi api = mock(IdentityApi.class);
    private final IdentityFacade facade = new IdentityFacade(mock(MockIdentity.class), api);

    @Test
    void interruptedResolveExternalIdDoesNotLeakPermit() {
        Semaphore semaphore = new Semaphore(1);
        Thread.currentThread().interrupt();
        try {
            assertNull(facade.resolveExternalId2GlobalId(new ID("type", "x"), false, semaphore));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted(); // clear for other tests
        }
        assertEquals(1, semaphore.availablePermits());
        verifyNoInteractions(api);
    }

    @Test
    void interruptedResolveGlobalIdDoesNotLeakPermit() {
        Semaphore semaphore = new Semaphore(1);
        Thread.currentThread().interrupt();
        try {
            assertNull(facade.resolveGlobalId2ExternalId(new GId("1"), "type", false, semaphore));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, semaphore.availablePermits());
        verifyNoInteractions(api);
    }
}
