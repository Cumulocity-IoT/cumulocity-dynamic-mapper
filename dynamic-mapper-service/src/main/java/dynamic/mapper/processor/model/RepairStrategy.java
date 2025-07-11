package dynamic.mapper.processor.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Strategies for handling edge cases during field substitution and data transformation")
public enum RepairStrategy {
    
    @Schema(description = "Process substitution as defined without any special handling")
    DEFAULT,
    
    @Schema(description = "If extracted content from source is an array, use only the first element")
    USE_FIRST_VALUE_OF_ARRAY,
    
    @Schema(description = "If extracted content from source is an array, use only the last element") 
    USE_LAST_VALUE_OF_ARRAY,
    
    @Schema(description = "Skip this substitution if source path evaluation fails")
    IGNORE,
    
    @Schema(description = "Remove the target node if source evaluation returns undefined, null, or empty. Enables dynamic content handling")
    REMOVE_IF_MISSING_OR_NULL,
    
    @Schema(description = "Create the target node if it doesn't exist. Enables dynamic content creation")
    CREATE_IF_MISSING
}