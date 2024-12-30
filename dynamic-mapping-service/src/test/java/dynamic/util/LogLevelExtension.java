package dynamic.util;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dynamic.mapping.model.MappingTreeNode;
import org.slf4j.LoggerFactory;

public class LogLevelExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        Logger logger = (Logger) LoggerFactory.getLogger(MappingTreeNode.class);
        logger.setLevel(Level.DEBUG);
    }
}

