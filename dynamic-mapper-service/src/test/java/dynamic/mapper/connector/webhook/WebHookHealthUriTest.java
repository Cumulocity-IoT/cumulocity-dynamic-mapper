package dynamic.mapper.connector.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

import dynamic.mapper.connector.core.client.ConnectorException;

class WebHookHealthUriTest {

    private static URI resolve(String base, String health) throws ConnectorException {
        return WebHook.healthUri(health).apply(new DefaultUriBuilderFactory(base).builder());
    }

    @Test
    void relativePathStaysOnBaseHost() throws ConnectorException {
        assertEquals(URI.create("https://hooks.example.com/api/health"),
                resolve("https://hooks.example.com/api", "/health"));
        assertEquals(URI.create("https://hooks.example.com/health?deep=true"),
                resolve("https://hooks.example.com", "/health?deep=true"));
    }

    @Test
    void absoluteOrHostBearingValuesAreRejected() {
        for (String bad : new String[] { "http://169.254.169.254/latest/meta-data", "https://evil.example/x",
                "//evil.example/x", "file:///etc/passwd", "/health#frag" }) {
            assertThrows(ConnectorException.class, () -> WebHook.healthUri(bad), bad);
        }
    }
}
