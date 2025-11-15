package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;

/**
 * JavaScript processor for calling the onMessage function with proper GraalJS interoperability
 */
public interface JavaScriptProcessor {
    
    /**
     * Calls the JavaScript onMessage function
     * @param msg Either a DeviceMessage or CumulocityObject (union type handled by Value)
     * @param context The flow context
     * @return Array of DeviceMessage or CumulocityObject objects as a Value
     */
    Value onMessage(Value msg, FlowContext context);
}