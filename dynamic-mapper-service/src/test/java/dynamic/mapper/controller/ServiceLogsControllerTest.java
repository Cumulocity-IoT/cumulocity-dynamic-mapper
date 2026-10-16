package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.logs.ServiceLogCollectorJob;
import dynamic.mapper.logs.ServiceLogReader;
import org.springframework.beans.factory.ObjectProvider;

class ServiceLogsControllerTest {

    private ContextService<UserCredentials> contextService;
    private ServiceLogReader.Events events;
    private ServiceLogsController controller;
    private ServiceLogCollectorJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn("tenantA");
        when(contextService.getContext()).thenReturn(credentials);
        events = mock(ServiceLogReader.Events.class);
        job = mock(ServiceLogCollectorJob.class);
        ObjectProvider<ServiceLogCollectorJob> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(job);
        controller = new ServiceLogsController(contextService, events, 10, provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void grant(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "n/a", List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void aUserWithoutTheAdminRoleCannotReadTheLogAndNothingIsQueried() {
        grant("ROLE_DYNAMIC_MAPPER_CREATE");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.read(null, null, null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(events, never()).list(any(), any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void anAdminReadsTheirOwnTenantsLogOnly() {
        grant("ROLE_DYNAMIC_MAPPER_ADMIN");
        when(events.list(any(), any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        var response = controller.read(null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().empty());
        verify(events).list(org.mockito.ArgumentMatchers.eq("tenantA"), any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void malformedDatesAreABadRequest() {
        grant("ROLE_DYNAMIC_MAPPER_ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.read(null, "yesterday", null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void flushStoresOnlyTheCallersTenantAndRequiresTheAdminRole() {
        grant("ROLE_DYNAMIC_MAPPER_CREATE");
        assertThrows(ResponseStatusException.class, () -> controller.flush());
        verify(job, never()).flushTenant(any());

        grant("ROLE_DYNAMIC_MAPPER_ADMIN");
        controller.flush();
        verify(job).flushTenant("tenantA");
    }
}
