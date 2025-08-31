package dynamic.mapper.processor.inbound;

import dynamic.mapper.processor.model.ProcessingContext;

public interface MessageSender<T> {
    /**
     * Process the context and send to Cumulocity
     * This corresponds to substituteInTargetAndSend in the original code
     */
    void send(ProcessingContext<T> context) throws Exception;
}
