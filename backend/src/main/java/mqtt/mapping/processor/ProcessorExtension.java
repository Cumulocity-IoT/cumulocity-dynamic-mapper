package mqtt.mapping.processor;

import org.springframework.stereotype.Component;

@Component
public interface ProcessorExtension<O> {
    public abstract void extractFromSource(ProcessingContext<O> context) throws ProcessingException;
}
