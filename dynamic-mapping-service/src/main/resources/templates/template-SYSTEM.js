/**
 * @name System code
 * @description System code containing the required definitions of the java classes
 * @templateType SYSTEM
 * @defaultTemplate true
 * @internal true
 * @readonly true
 */

const ArrayList = Java.type('java.util.ArrayList');
const HashMap = Java.type('java.util.HashMap');

/*
 * @class SubstitutionResult
 * @classdesc Represents the result of a substitution extraction operation.
 * Contains a map of substitution values and status information about the extraction process.
 * This object should be populated using the method addSubstitution(result, key, value).
 * 
 * @property {Object.<string, SubstitutionValue>} substitutions - Key-value pairs of substitution values
 * 
 * @example
 * // Create a new substitution result
 * const result = new SubstitutionResult();
 * 
 * // Add values to the substitutions map
 * addSubstitution(result, "temperature", new SubstitutionValue(23.5, TYPE.NUMBER, RepairStrategy.DEFAULT, false));
 * addSubstitution(result, "deviceStatus", new SubstitutionValue("ACTIVE", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
 * 
 * return result;
 */
const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');

/*
 * Represents a value for substitution during the mapping process.
 * 
 * @class
 * @classdesc Handles extracted values and their processing during substitution.
 * 
 * @param {*} value - The extracted value from the source payload
 * @param {TYPE} type - The type of the extracted value (ARRAY, IGNORE, NUMBER, OBJECT, TEXTUAL)
 * @param {RepairStrategy} repairStrategy - Strategy that defines how substitution should be processed
 * @param {boolean} expandArray - When true, an array will generate multiple entities; e.g., [10.5, 30, 49] will generate 3 separate measurements from one payload
 * 
 * @example
 * // Create a substitution value for a numeric array with expansion, i.e. create multiple measurements
 * const subValue = new SubstitutionValue([10.5, 30, 49], TYPE.ARRAY, RepairStrategy.DEFAULT, true);
 */
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');


/*
 * Enum of data types that describe the extracted data's format during mapping.
 * 
 * @typedef {string} TYPE
 * 
 * @property {string} ARRAY - Extracted data is an array
 * @property {string} IGNORE - Extracted data should be ignored during processing
 * @property {string} NUMBER - Extracted data is a numeric value
 * @property {string} OBJECT - Extracted data is an object, e.g. {"c8y_ThreePhaseElectricityMeasurement": {"A+": { "value": 435, "unit": "kWh" }}}
 * @property {string} TEXTUAL - Extracted data is text/string
 */
const TYPE = Java.type('dynamic.mapping.processor.model.SubstituteValue$TYPE');

/*
 * Enum of available substitution modes that control how values are processed during mapping.
 * 
 * @typedef {string} RepairStrategy
 * 
 * @property {string} DEFAULT - Process substitution as defined
 * @property {string} USE_FIRST_VALUE_OF_ARRAY - If extracted content from the source payload is an array, copy only the first item to the target payload
 * @property {string} USE_LAST_VALUE_OF_ARRAY - If extracted content from the source payload is an array, copy only the last item to the target payload
 * @property {string} REMOVE_IF_MISSING_OR_NULL - Remove the node in the target if the evaluation of the source expression returns undefined or empty; allows for mapping with dynamic content
 * @property {string} CREATE_IF_MISSING - Create the node in the target if it doesn't exist; allows for mapping with dynamic content
 */
const RepairStrategy = Java.type('dynamic.mapping.processor.model.RepairStrategy');

/*
 * Extracts data from the source payload to be used in substitutions during mapping.
 * 
 * This function is called during the evaluation at runtime to define the substitution values
 * that will be applied to the target payload. It analyzes the source payload and
 * creates the necessary substitution values based on the mapping configuration.
 * 
 * @function extractFromSource
 * @param {SubstitutionContext} ctx - Context object containing:
 *   @param {Object} ctx.getPayload() - The source payload to extract data from
 *   @param {string} ctx.getGenericDeviceIdentifier() - Name of device identifier, i.e. either "_IDENTITY_.externalId" or "_IDENTITY_.c8ySourceId" 
 *   @param {string} ctx.getExternalDeviceIdentifier() - Device identifier used in external systems
 *   @param {string} ctx.getC8YDeviceIdentifier() - Cumulocity platform device identifier
 * 
 * @returns {SubstitutionResult} A result object populated using method addSubstitution(result, key, value), containing:
 *   @returns {Object.<string, SubstitutionValue>} substitutions - Key-value pairs of substitution values
 */
function addSubstitution(result, key, value) {
    let map = result.getSubstitutions();
    let valuesList = map.get(key);

    // If the list doesn't exist for this key, create it
    if (valuesList === null || valuesList === undefined) {
        valuesList = new ArrayList();
        map.put(key, valuesList);
    }

    // Add the value to the list
    valuesList.add(value);
}

/*
 * Trace payload and device identifiers data from the source payload to be used in substitutions during mapping.
 */
function tracePayload(ctx) {
    const sourceObject = JSON.parse(ctx.getPayload());
    for (var key in sourceObject) {
        console.log(`Payload key: ${key}, value: ${sourceObject[key]}`);
    }
    console.log(`Identifier sourceId: ${ctx.getC8YIdentifier()}`);
    console.log(`Identifier externalIdentifier: ${ctx.getExternalIdentifier()}`);
    console.log(`Identifier genericDeviceIdentifier: ${ctx.getGenericDeviceIdentifier()}`);
}