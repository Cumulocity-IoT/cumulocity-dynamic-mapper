package dynamic.mapper.logs.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;

import dynamic.mapper.core.IMapperConfiguration;

class ServiceLogSourceLocatorTest {

    private InventoryApi inventoryApi;
    private IdentityApi identityApi;
    private IMapperConfiguration mapperConfiguration;
    private ServiceLogSourceLocator locator;

    @BeforeEach
    void setUp() {
        inventoryApi = mock(InventoryApi.class);
        identityApi = mock(IdentityApi.class);
        mapperConfiguration = mock(IMapperConfiguration.class);
        locator = new ServiceLogSourceLocator(inventoryApi, identityApi, mapperConfiguration);
    }

    private ExternalIDRepresentation externalIdOf(String moId) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(moId));
        ExternalIDRepresentation external = new ExternalIDRepresentation();
        external.setManagedObject(mo);
        return external;
    }

    @Test
    void findDoesNotCreateAnythingWhenNothingWasEverCollected() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"));

        assertTrue(locator.find("tenantA").isEmpty());

        verify(inventoryApi, never()).create(any());
        verify(identityApi, never()).create(any());
    }

    @Test
    void aFailureOtherThanNotFoundIsNotHiddenAsEmpty() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(500, "boom"));

        assertThrows(SDKException.class, () -> locator.find("tenantA"));
    }

    @Test
    void getOrCreateCreatesTheSourceOnceAndRegistersItsExternalId() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"));
        ManagedObjectRepresentation created = new ManagedObjectRepresentation();
        created.setId(new GId("4711"));
        when(inventoryApi.create(any())).thenReturn(created);

        GId first = locator.getOrCreate("tenantA");
        GId second = locator.getOrCreate("tenantA");

        assertEquals("4711", first.getValue());
        assertEquals(first, second);
        verify(inventoryApi, times(1)).create(any());
        verify(identityApi, times(1)).create(any(ExternalIDRepresentation.class));
        assertEquals(1, org.mockito.Mockito.mockingDetails(identityApi).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("getExternalId")).count(),
                "the second call is served from the cache");
    }

    @Test
    void anExistingSourceIsReusedNotDuplicated() {
        when(identityApi.getExternalId(any(ID.class))).thenReturn(externalIdOf("99"));

        GId id = locator.getOrCreate("tenantA");

        assertEquals("99", id.getValue());
        verify(inventoryApi, never()).create(any());
    }

    @Test
    void aFailureToLinkTheSourceToTheAgentDoesNotStopLogCollection() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"));
        ManagedObjectRepresentation created = new ManagedObjectRepresentation();
        created.setId(new GId("4711"));
        when(inventoryApi.create(any())).thenReturn(created);
        when(mapperConfiguration.getMapperServiceRepresentation("tenantA"))
                .thenThrow(new IllegalStateException("no agent"));

        assertEquals("4711", locator.getOrCreate("tenantA").getValue());
    }

    @Test
    void aTenantThatUnsubscribesIsForgottenSoAReSubscriptionNeverReusesAStaleId() {
        when(identityApi.getExternalId(any(ID.class))).thenReturn(externalIdOf("99"));
        locator.find("tenantA");
        locator.find("tenantB");

        MicroserviceSubscriptionRemovedEvent event = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(event.getTenant()).thenReturn("tenantA");
        locator.onUnsubscribed(event);
        locator.find("tenantA");
        locator.find("tenantB");

        // tenantA was looked up again after the event; tenantB came from the cache
        verify(identityApi, times(3)).getExternalId(any(ID.class));
    }

    @Test
    void whenTwoInstancesCreateTheSourceAtTheSameTimeTheLoserUsesTheWinnerAndRemovesItsOwnObject() {
        // our find: 404; then identity create loses the race (409); the second find returns the winner
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"))
                .thenReturn(externalIdOf("WINNER"));
        ManagedObjectRepresentation ours = new ManagedObjectRepresentation();
        ours.setId(new GId("OURS"));
        when(inventoryApi.create(any())).thenReturn(ours);
        org.mockito.Mockito.doThrow(new SDKException(409, "already exists")).when(identityApi)
                .create(any(ExternalIDRepresentation.class));

        GId id = locator.getOrCreate("tenantA");

        assertEquals("WINNER", id.getValue());
        verify(inventoryApi).delete(new GId("OURS"));
    }

    @Test
    void aFailedExternalIdRegistrationThatIsNotARaceIsNotHidden() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"));
        ManagedObjectRepresentation ours = new ManagedObjectRepresentation();
        ours.setId(new GId("OURS"));
        when(inventoryApi.create(any())).thenReturn(ours);
        org.mockito.Mockito.doThrow(new SDKException(500, "boom")).when(identityApi).create(any(ExternalIDRepresentation.class));

        assertThrows(SDKException.class, () -> locator.getOrCreate("tenantA"));
        verify(inventoryApi).delete(new GId("OURS"));
    }

    @Test
    void whenThePlatformStaysDownAfterTheObjectWasCreatedTheObjectIsStillRemovedAndTheErrorIsReported() {
        // find: 404, then the platform fails for the registration and for the lookup that follows it
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"))
                .thenThrow(new SDKException(502, "Bad Gateway"));
        ManagedObjectRepresentation ours = new ManagedObjectRepresentation();
        ours.setId(new GId("OURS"));
        when(inventoryApi.create(any())).thenReturn(ours);
        org.mockito.Mockito.doThrow(new SDKException(502, "Bad Gateway")).when(identityApi)
                .create(any(ExternalIDRepresentation.class));

        SDKException failure = assertThrows(SDKException.class, () -> locator.getOrCreate("tenantA"));

        assertEquals(502, failure.getHttpStatus());
        assertEquals(1, failure.getSuppressed().length, "the failed lookup is kept as a suppressed cause");
        verify(inventoryApi).delete(new GId("OURS"));
    }

    @Test
    void aFailedCleanupDoesNotReplaceTheOriginalError() {
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"))
                .thenThrow(new SDKException(502, "Bad Gateway"));
        ManagedObjectRepresentation ours = new ManagedObjectRepresentation();
        ours.setId(new GId("OURS"));
        when(inventoryApi.create(any())).thenReturn(ours);
        org.mockito.Mockito.doThrow(new SDKException(502, "Bad Gateway")).when(identityApi)
                .create(any(ExternalIDRepresentation.class));
        org.mockito.Mockito.doThrow(new SDKException(502, "Bad Gateway")).when(inventoryApi).delete(any(GId.class));

        SDKException failure = assertThrows(SDKException.class, () -> locator.getOrCreate("tenantA"));

        assertEquals(502, failure.getHttpStatus());
    }

    @Test
    void aRegistrationThatTheAnswerWasLostForIsKeptNotDeleted() {
        // the platform stored the external id but the response was lost: the lookup now returns our own object
        when(identityApi.getExternalId(any(ID.class))).thenThrow(new SDKException(404, "not found"))
                .thenReturn(externalIdOf("OURS"));
        ManagedObjectRepresentation ours = new ManagedObjectRepresentation();
        ours.setId(new GId("OURS"));
        when(inventoryApi.create(any())).thenReturn(ours);
        org.mockito.Mockito.doThrow(new SDKException(502, "Bad Gateway")).when(identityApi)
                .create(any(ExternalIDRepresentation.class));

        assertEquals("OURS", locator.getOrCreate("tenantA").getValue());

        verify(inventoryApi, never()).delete(any(GId.class));
    }

    @Test
    void evictingForgetsTheSourceSoADeletedObjectIsLookedUpAgain() {
        when(identityApi.getExternalId(any(ID.class))).thenReturn(externalIdOf("99"));
        locator.find("tenantA");
        locator.find("tenantA"); // cached

        locator.evict("tenantA");
        locator.find("tenantA");

        verify(identityApi, times(2)).getExternalId(any(ID.class));
    }
}
