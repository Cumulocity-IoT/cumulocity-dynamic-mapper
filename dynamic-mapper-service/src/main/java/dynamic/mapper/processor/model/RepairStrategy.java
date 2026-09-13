package dynamic.mapper.processor.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Per-substitution strategy for the two edge cases a substitution can hit:
 * the extracted source value is an <em>array</em> where a single value is
 * expected, or it is <em>missing/null</em> when the target document is built.
 *
 * <p>
 * The strategies apply at two different stages of the pipeline:
 * <ul>
 * <li><b>Extraction</b> ({@code AbstractJSONataExtractionProcessor}) —
 * {@link #USE_FIRST_VALUE_OF_ARRAY} and {@link #USE_LAST_VALUE_OF_ARRAY} reduce
 * an extracted array to one element. They only take effect for JSONATA
 * substitutions and only when {@code expandArray} is <b>not</b> set; with
 * {@code expandArray} the array is fanned out into one substitution per element
 * instead, so there is nothing to reduce. Smart Functions and Java extensions
 * build their {@code SubstituteValue}s directly and are unaffected.</li>
 * <li><b>Target write</b>
 * ({@link SubstituteValue#substituteValueInPayload}) — {@link #IGNORE},
 * {@link #REMOVE_IF_MISSING_OR_NULL} and {@link #CREATE_IF_MISSING} decide how
 * the value is written into the target template. These apply to all
 * transformation types.</li>
 * </ul>
 */
@Schema(description = "Per-substitution strategy for handling array results during extraction and missing/null values when writing into the target template")
public enum RepairStrategy {

    @Schema(description = "Write the extracted value to the target path as-is. The path must already exist in the target template, otherwise processing fails with 'Path: <pathTarget> not found!'")
    DEFAULT,

    @Schema(description = "If the extracted value is an array and expandArray is not set, use only its first element. JSONATA substitutions only")
    USE_FIRST_VALUE_OF_ARRAY,

    @Schema(description = "If the extracted value is an array and expandArray is not set, use only its last element. JSONATA substitutions only")
    USE_LAST_VALUE_OF_ARRAY,

    @Schema(description = "If the extracted value is missing or null, skip the substitution and leave the target node as defined in the target template (e.g. keeping a default value)")
    IGNORE,

    @Schema(description = "If the extracted value is missing or null, delete the target node from the target template. Removing a node that does not exist is a no-op")
    REMOVE_IF_MISSING_OR_NULL,

    @Schema(description = "Create the target node, including any missing parent nodes, if it does not exist in the target template. Required for any pathTarget that is not a literal path of the target template")
    CREATE_IF_MISSING
}
