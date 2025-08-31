package dynamic.mapper.processor.inbound;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseMessageSender<T> implements MessageSender<T> {
    
    @Override
    public void send(ProcessingContext<T> context) throws Exception {
        Mapping mapping = context.getMapping();
        
        // Create and add request
        C8YRequest request = createRequest (context);
        context.addRequest(request);
        
        log.debug("Created {} request for mapping: {}", getAPI(), mapping.getName());
    }
    
    /**
     * Create the C8Y request based on processed template and context
     */
    protected abstract C8YRequest createRequest(ProcessingContext<T> context);
    
    /**
     * Get the API type this sender handles
     */
    protected abstract API getAPI();
}