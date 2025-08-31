package dynamic.mapper.processor.inbound;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseMessageSender<T> implements MessageSender<T> {
    
    private static final Pattern SUBSTITUTION_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    
    @Override
    public void send(ProcessingContext<T> context) throws Exception {
        Mapping mapping = context.getMapping();
        
        if (mapping.getTarget() == null || mapping.getTarget().trim().isEmpty()) {
            log.warn("No target template defined for mapping: {}", mapping.getName());
            return;
        }
        
        // Substitute variables in target template
        String processedTarget = substituteVariables(mapping.getTarget(), context);
        
        // Create and add request
        C8YRequest request = createRequest(processedTarget, context);
        context.addRequest(request);
        
        log.debug("Created {} request for mapping: {}", getAPI(), mapping.getName());
    }
    
    /**
     * Substitute variables in the target template using processing cache
     */
    protected String substituteVariables(String template, ProcessingContext<T> context) {
        if (template == null || template.trim().isEmpty()) {
            return template;
        }
        
        String result = template;
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        
        if (processingCache == null || processingCache.isEmpty()) {
            log.debug("No processing cache available for substitution");
            return result;
        }
        
        Matcher matcher = SUBSTITUTION_PATTERN.matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group(0); // ${variable}
            String variableName = matcher.group(1); // variable
            
            List<SubstituteValue> values = processingCache.get(variableName);
            if (values != null && !values.isEmpty()) {
                SubstituteValue substituteValue = values.get(0);
                String replacementValue = getValueAsString(substituteValue);
                
                if (replacementValue != null) {
                    result = result.replace(placeholder, replacementValue);
                    log.debug("Substituted {} with: {}", placeholder, replacementValue);
                } else {
                    log.debug("No value found for placeholder: {}", placeholder);
                }
            } else {
                log.debug("No substitution value found for: {}", variableName);
            }
        }
        
        return result;
    }
    
    /**
     * Convert SubstituteValue to string
     */
    protected String getValueAsString(SubstituteValue substituteValue) {
        if (substituteValue == null || substituteValue.getValue() == null) {
            return null;
        }
        
        Object value = substituteValue.getValue();
        
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else {
            return value.toString();
        }
    }
    
    /**
     * Create the C8Y request based on processed template and context
     */
    protected abstract C8YRequest createRequest(String processedTarget, ProcessingContext<T> context);
    
    /**
     * Get the API type this sender handles
     */
    protected abstract API getAPI();
}