package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The HTTP connector answers 200 only after the pipeline succeeded, and 500 otherwise, so the sender can
 * retry. It used to answer 200 as soon as the message was handed over.
 */
class HttpConnectorControllerAcknowledgementTest {

    private HttpClient client;
    private HttpConnectorController controller;
    private HttpServletRequest request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        client = mock(HttpClient.class);
        when(registry.getHttpConnectorForTenant("t1")).thenReturn(client);
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setProperties(Map.of(HttpClient.PROPERTY_CUTOFF_LEADING_SLASH, true));
        when(client.getConnectorConfiguration()).thenReturn(configuration);

        com.cumulocity.microservice.context.ContextService<UserCredentials> contextService =
                mock(com.cumulocity.microservice.context.ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn("t1");
        when(contextService.getContext()).thenReturn(credentials);
        controller = new HttpConnectorController(registry, contextService);

        request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("");
        when(request.getRequestURI()).thenReturn(HttpClient.HTTP_CONNECTOR_ABSOLUTE_PATH + "/device/1");
        when(request.getInputStream()).thenReturn(new ServletInputStream() {
            private final ByteArrayInputStream in = new ByteArrayInputStream("{}".getBytes());

            @Override public int read() { return in.read(); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener l) { }
        });
    }

    @Test
    void answers200AfterASuccessfulPipeline() {
        doReturn(ProcessingResultHelper.success(List.<ProcessingContext<Object>>of())).when(client).onMessage(any());

        ResponseEntity<?> response = controller.processGenericMessage(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void answers500WhenThePipelineFailedSoTheSenderCanRetry() {
        CompletableFuture<List<ProcessingContext<Object>>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.successAsync(failed, Qos.AT_LEAST_ONCE, 1000);
        doReturn(result).when(client).onMessage(any());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.processGenericMessage(request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, thrown.getStatusCode());
    }

    @Test
    void answers500WhenThePipelineTimesOut() {
        ProcessingResultWrapper<Object> result = ProcessingResultHelper.successAsync(
                new CompletableFuture<List<ProcessingContext<Object>>>(), Qos.AT_LEAST_ONCE, 50);
        doReturn(result).when(client).onMessage(any());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.processGenericMessage(request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, thrown.getStatusCode());
    }
}
