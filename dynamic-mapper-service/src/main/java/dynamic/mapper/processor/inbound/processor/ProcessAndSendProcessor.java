package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.sender.AlarmSender;
import dynamic.mapper.processor.inbound.sender.EventSender;
import dynamic.mapper.processor.inbound.sender.MeasurementSender;
import dynamic.mapper.processor.inbound.sender.MessageSender;
import dynamic.mapper.processor.model.ProcessingContext;

@Component
public class ProcessAndSendProcessor implements Processor {
    
    private final Map<String, MessageSender> senders = new HashMap<>();
    
    public ProcessAndSendProcessor() {
        senders.put("MEASUREMENT", new MeasurementSender());
        senders.put("EVENT", new EventSender());
        senders.put("ALARM", new AlarmSender());
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContext(exchange);
        Mapping mapping = context.getMapping();
        
        // Determine target API from mapping
        API targetAPI = determineTargetAPI(mapping);
        context.setApi(targetAPI);
        
        // Get appropriate sender
        MessageSender sender = senders.get(targetAPI.toString());
        if (sender == null) {
            throw new ProcessingException("No sender found for target API: " + targetAPI);
        }
        
        // Process and send - this will populate the context's requests list
        sender.send(context);
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    private API determineTargetAPI(Mapping mapping) {
        // Logic to determine API based on mapping configuration
        // This might be stored in the mapping or determined from the target template
        if (mapping.getTargetAPI() != null) {
            return (mapping.getTargetAPI());
        }
        return API.MEASUREMENT; // Default
    }
    
    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> getProcessingContext(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}
