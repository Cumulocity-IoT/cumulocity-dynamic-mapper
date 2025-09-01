package dynamic.mapper.processor.inbound.processor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FilterInboundProcessor extends BaseProcessor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContextAsObject", ProcessingContext.class);
        
        boolean shouldProcess = applyFilter(context);
        
        if (!shouldProcess) {
            // Set flag to stop processing this message
            context.setIgnoreFurtherProcessing(true);
            log.debug("Message filtered out for mapping: {}, topic: {}", 
                context.getMapping().getName(), context.getTopic());
        }
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    /**
     * Apply filter based on the original BaseProcessorInbound.applyFilter logic
     */
    public boolean applyFilter(ProcessingContext<Object> context) {
        Mapping mapping = context.getMapping();
        
        // If no filter is defined, allow processing
        if (mapping.getFilterMapping() == null || mapping.getFilterMapping().trim().isEmpty()) {
            log.debug("No filter defined for mapping: {}, allowing processing", mapping.getName());
            return true;
        }
        
        try {
            String filterExpression = mapping.getFilterMapping().trim();
            log.debug("Applying filter: {} for mapping: {}", filterExpression, mapping.getName());
            
            // Substitute variables in the filter expression using processing cache
            String resolvedFilter = substituteVariablesInFilter(filterExpression, context);
            log.debug("Resolved filter expression: {}", resolvedFilter);
            
            // Evaluate the filter expression
            boolean result = evaluateFilterExpression(resolvedFilter, context);
            log.debug("Filter evaluation result: {} for mapping: {}", result, mapping.getName());
            
            return result;
            
        } catch (Exception e) {
            log.error("Error applying filter for mapping: {}, topic: {}. Error: {}", 
                mapping.getName(), context.getTopic(), e.getMessage(), e);
            
            // On error, decide whether to allow or deny processing
            // Based on the original code behavior, we'll allow processing on filter errors
            return true;
        }
    }
    
    /**
     * Substitute variables in filter expression using the processing cache
     */
    private String substituteVariablesInFilter(String filterExpression, ProcessingContext<Object> context) {
        String result = filterExpression;
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        
        if (processingCache == null || processingCache.isEmpty()) {
            return result;
        }
        
        // Replace variables in the format ${variable} or #{variable}
        for (Map.Entry<String, List<SubstituteValue>> entry : processingCache.entrySet()) {
            String key = entry.getKey();
            List<SubstituteValue> values = entry.getValue();
            
            if (values != null && !values.isEmpty()) {
                SubstituteValue firstValue = values.get(0);
                String substitutionValue = getSubstitutionValueAsString(firstValue);
                
                // Replace various variable formats
                result = result.replaceAll("\\$\\{" + Pattern.quote(key) + "\\}", 
                    substitutionValue != null ? substitutionValue : "null");
                result = result.replaceAll("#\\{" + Pattern.quote(key) + "\\}", 
                    substitutionValue != null ? substitutionValue : "null");
                result = result.replaceAll("\\{" + Pattern.quote(key) + "\\}", 
                    substitutionValue != null ? substitutionValue : "null");
            }
        }
        
        return result;
    }
    
    /**
     * Convert SubstituteValue to string for filter evaluation
     */
    private String getSubstitutionValueAsString(SubstituteValue substituteValue) {
        if (substituteValue == null || substituteValue.getValue() == null) {
            return null;
        }
        
        Object value = substituteValue.getValue();
        
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof Boolean) {
            return value.toString();
        } else {
            return value.toString();
        }
    }
    
    /**
     * Evaluate the resolved filter expression
     * This supports basic comparison operations like ==, !=, >, <, >=, <=, contains, etc.
     */
    private boolean evaluateFilterExpression(String expression, ProcessingContext<Object> context) {
        try {
            // Remove extra whitespace
            expression = expression.trim();
            
            // Handle boolean literals
            if ("true".equalsIgnoreCase(expression)) {
                return true;
            }
            if ("false".equalsIgnoreCase(expression)) {
                return false;
            }
            
            // Handle null checks
            if (expression.contains("null")) {
                return evaluateNullExpression(expression);
            }
            
            // Handle string contains operations
            if (expression.contains(".contains(")) {
                return evaluateContainsExpression(expression);
            }
            
            // Handle equality operations
            if (expression.contains("==")) {
                return evaluateEqualityExpression(expression, "==");
            }
            
            // Handle inequality operations
            if (expression.contains("!=")) {
                return evaluateEqualityExpression(expression, "!=");
            }
            
            // Handle greater than or equal
            if (expression.contains(">=")) {
                return evaluateNumericExpression(expression, ">=");
            }
            
            // Handle less than or equal
            if (expression.contains("<=")) {
                return evaluateNumericExpression(expression, "<=");
            }
            
            // Handle greater than
            if (expression.contains(">")) {
                return evaluateNumericExpression(expression, ">");
            }
            
            // Handle less than
            if (expression.contains("<")) {
                return evaluateNumericExpression(expression, "<");
            }
            
            // Handle logical AND
            if (expression.contains(" && ") || expression.contains(" and ")) {
                return evaluateLogicalExpression(expression, context, true);
            }
            
            // Handle logical OR
            if (expression.contains(" || ") || expression.contains(" or ")) {
                return evaluateLogicalExpression(expression, context, false);
            }
            
            // If no recognized operation, try to evaluate as boolean
            // This handles cases where the expression is just a variable name
            return evaluateAsBoolean(expression);
            
        } catch (Exception e) {
            log.warn("Failed to evaluate filter expression: {}. Error: {}", expression, e.getMessage());
            // Return true on evaluation errors to allow processing
            return true;
        }
    }
    
    private boolean evaluateNullExpression(String expression) {
        if (expression.contains("== null") || expression.contains("==null")) {
            String leftSide = expression.split("==")[0].trim();
            return "null".equals(leftSide);
        }
        if (expression.contains("!= null") || expression.contains("!=null")) {
            String leftSide = expression.split("!=")[0].trim();
            return !"null".equals(leftSide);
        }
        return false;
    }
    
    private boolean evaluateContainsExpression(String expression) {
        // Handle expressions like "someString.contains('value')"
        int containsIndex = expression.indexOf(".contains(");
        if (containsIndex > 0) {
            String leftSide = expression.substring(0, containsIndex).trim();
            String rightSide = expression.substring(containsIndex + 10); // Skip ".contains("
            rightSide = rightSide.substring(0, rightSide.lastIndexOf(')')).trim();
            
            // Remove quotes from right side
            rightSide = removeQuotes(rightSide);
            
            if ("null".equals(leftSide) || leftSide.isEmpty()) {
                return false;
            }
            
            return leftSide.contains(rightSide);
        }
        return false;
    }
    
    private boolean evaluateEqualityExpression(String expression, String operator) {
        String[] parts = expression.split(operator);
        if (parts.length != 2) {
            return false;
        }
        
        String left = parts[0].trim();
        String right = parts[1].trim();
        
        // Remove quotes if present
        left = removeQuotes(left);
        right = removeQuotes(right);
        
        boolean isEqual = left.equals(right);
        return "==".equals(operator) ? isEqual : !isEqual;
    }
    
    private boolean evaluateNumericExpression(String expression, String operator) {
        String[] parts = expression.split(operator);
        if (parts.length != 2) {
            return false;
        }
        
        try {
            double left = Double.parseDouble(parts[0].trim());
            double right = Double.parseDouble(parts[1].trim());
            
            switch (operator) {
                case ">":
                    return left > right;
                case "<":
                    return left < right;
                case ">=":
                    return left >= right;
                case "<=":
                    return left <= right;
                default:
                    return false;
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse numeric values in expression: {}", expression);
            return false;
        }
    }
    
    private boolean evaluateLogicalExpression(String expression, ProcessingContext<Object> context, boolean isAnd) {
        String[] parts;
        if (isAnd) {
            parts = expression.contains(" && ") ? expression.split(" && ") : expression.split(" and ");
        } else {
            parts = expression.contains(" || ") ? expression.split(" \\|\\| ") : expression.split(" or ");
        }
        
        if (isAnd) {
            // All parts must be true for AND
            for (String part : parts) {
                if (!evaluateFilterExpression(part.trim(), context)) {
                    return false;
                }
            }
            return true;
        } else {
            // Any part can be true for OR
            for (String part : parts) {
                if (evaluateFilterExpression(part.trim(), context)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    private boolean evaluateAsBoolean(String expression) {
        // Try to parse as boolean
        if ("true".equalsIgnoreCase(expression)) {
            return true;
        }
        if ("false".equalsIgnoreCase(expression) || "null".equals(expression) || expression.isEmpty()) {
            return false;
        }
        
        // If it's a non-empty string that's not "false" or "null", consider it true
        return !expression.trim().isEmpty();
    }
    
    private String removeQuotes(String value) {
        if (value == null) {
            return null;
        }
        
        value = value.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || 
            (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
    
}