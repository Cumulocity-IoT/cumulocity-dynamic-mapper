package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GraalVMSourceNameTest {

    @Test
    void theIdentifierIsTakenFromASmartFunctionSourceName() {
        assertEquals("9n2el5vs", GraalVMContextService.identifierOfSource("onMessage_9n2el5vs.js"));
        assertEquals("ibs58061", GraalVMContextService.identifierOfSource("onMessage_ibs58061.mjs"));
        assertEquals("alarm-66994", GraalVMContextService.identifierOfSource("onMessage_alarm-66994.js"));
    }

    @Test
    void anythingElseIsNoMapping() {
        assertNull(GraalVMContextService.identifierOfSource(null));
        assertNull(GraalVMContextService.identifierOfSource("shared.js"));
        assertNull(GraalVMContextService.identifierOfSource("onMessage_.js"));
        assertNull(GraalVMContextService.identifierOfSource("onMessage_a b.js"), "not a valid identifier");
    }
}
