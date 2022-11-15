package mqtt.mapping.processor.extension;

import org.springframework.stereotype.Component;

import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;

@Component
public interface ProcessorExtension<O> {
    public abstract void extractFromSource(ProcessingContext<O> context) throws ProcessingException;
}
