package dynamic.mapper.core;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.core.PlatformProperties;
import com.cumulocity.model.authentication.CumulocityCredentials;
import com.cumulocity.model.authentication.CumulocityCredentialsFactory;
import com.cumulocity.sdk.client.*;
import com.cumulocity.sdk.client.interceptor.HttpClientInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.client.Invocation;
import java.util.concurrent.Callable;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingModeService {

    private final ContextService<MicroserviceCredentials> contextService;
    private final PlatformProperties platformProperties;

    public <T> T callWithProcessingMode(String processingMode, Callable<T> callable) throws Exception {
        final RestConnector connector = createRestConnector();
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        try {
            connector.getPlatformParameters().registerInterceptor(interceptor);
            log.debug("Registered {} processing mode interceptor", processingMode);
            return callable.call();
        } finally {
            connector.getPlatformParameters().unregisterInterceptor(interceptor);
            log.debug("Unregistered {} processing mode interceptor", processingMode);
        }
    }

    public void runWithProcessingMode(String processingMode, Runnable runnable) {
        final RestConnector connector = createRestConnector();
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        try {
            connector.getPlatformParameters().registerInterceptor(interceptor);
            log.debug("Registered {} processing mode interceptor", processingMode);
            runnable.run();
        } finally {
            connector.getPlatformParameters().unregisterInterceptor(interceptor);
            log.debug("Unregistered {} processing mode interceptor", processingMode);
        }
    }

    public Platform createPlatformWithProcessingMode(String processingMode) {
        final RestConnector connector = createRestConnector();
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        connector.getPlatformParameters().registerInterceptor(interceptor);
        log.debug("Created platform with {} processing mode", processingMode);
        
        //return new PlatformImpl(connector.getPlatformParameters());
        final PlatformParameters params = createPlatformParameters();

        return new PlatformImpl(platformProperties.getUrl().get(), params.getCumulocityCredentials());
    }

    private RestConnector createRestConnector() {
        final PlatformParameters params = createPlatformParameters();
        return new RestConnector(params, new ResponseParser());
    }

    private PlatformParameters createPlatformParameters() {
        final MicroserviceCredentials context = contextService.getContext();
        
        final CumulocityCredentials credentials = new CumulocityCredentialsFactory()
                .withUsername(context.getUsername())
                .withTenant(context.getTenant())
                .withPassword(context.getPassword())
                .withOAuthAccessToken(context.getOAuthAccessToken())
                .withXsrfToken(context.getXsrfToken())
                .withApplicationKey(context.getAppKey())
                .getCredentials();
        
        final PlatformParameters params = new PlatformParameters(
            platformProperties.getUrl().get(), 
            credentials, 
            new ClientConfiguration()
        );
        
        params.setForceInitialHost(platformProperties.getForceInitialHost());
        params.setTfaToken(context.getTfaToken());
        
        return params;
    }

    @RequiredArgsConstructor
    private static class ProcessingModeHttpClientInterceptor implements HttpClientInterceptor {

        private final String processingMode;

        @Override
        public Invocation.Builder apply(final Invocation.Builder builder) {
            return builder.header("X-Cumulocity-Processing-Mode", processingMode);
        }
    }
}
