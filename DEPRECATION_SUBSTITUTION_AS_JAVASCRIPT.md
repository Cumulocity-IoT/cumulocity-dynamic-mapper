# Deprecation Notice: Substitution as JavaScript

## Overview

**Substitution as JavaScript** (`TransformationType.SUBSTITUTION_AS_CODE`) is deprecated as of release **6.1.5** and will be removed in a future release.

Users are encouraged to migrate to **Smart Functions** for all new and existing mappings that require JavaScript-based transformations.

## Background

Substitution as JavaScript was introduced as an evolutionary and intermediate step in the transformation capabilities of the Dynamic Mapper, bridging the gap between **Substitution as JSONata Expression** and the more comprehensive **Smart Functions** approach.

### Purpose and Capabilities

Substitution as JavaScript extended the capabilities of JSONata expressions by:

- **Control structures**: Allowing the use of JavaScript control structures (`if`, `for`, `while`, etc.) to provide more granular control over the creation of substitutions
- **Dynamic logic**: Enabling complex transformation logic beyond simple path-based substitutions
- **Runtime flexibility**: Defining the target API dynamically at runtime based on payload content or business logic

### Examples of Substitution as JavaScript

#### Example 1: Creating a Single Measurement

**Using Substitution as JavaScript (Deprecated):**

```javascript
/**
 * Sample to generate one measurement
 * payload:
 * {
 *     "temperature": 139.0,
 *     "unit": "C",
 *     "externalId": "berlin_01"
 * }
 * topic: 'testGraalsSingle/berlin_01'
 */
function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    // Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperatureSeries = {
        value: sourceObject['temperature'],
        unit: sourceObject['unit']
    };

    // Assign Values to Series
    const fragmentTemperature = {
        T: fragmentTemperatureSeries
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    // Add temperature with key 'c8y_TemperatureMeasurement' to result.getSubstitutions()
    addSubstitution(result, 'c8y_TemperatureMeasurement', temperature);

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    // Add deviceIdentifier with key ctx.getGenericDeviceIdentifier() to result.getSubstitutions()
    addSubstitution(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}
```

**Migrated to Smart Function:**

```javascript
/**
 * Sample to generate one measurement using Smart Function
 * payload:
 * {
 *     "temperature": 139.0,
 *     "unit": "C",
 *     "externalId": "berlin_01"
 * }
 * topic: 'testGraalsSingle/berlin_01'
 */
function onMessage(inputMsg, context) {
    const msg = inputMsg;
    const payload = JSON.parse(msg.getPayload());

    // Extract device identifier from topic
    const topicLevels = msg.getTopic().split('/');
    const deviceId = topicLevels[1]; // 'berlin_01'

    // Directly create the measurement payload - no substitution objects needed!
    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": new Date().toISOString(),
            "type": "c8y_TemperatureMeasurement",
            "c8y_TemperatureMeasurement": {
                "T": {
                    "value": payload.temperature,
                    "unit": payload.unit
                }
            }
        },
        externalSource: [{
            "type": "c8y_Serial",
            "externalId": deviceId
        }]
    }];
}
```

**Key Improvements:**
- Direct payload creation instead of substitution objects
- Clear visibility of the exact measurement structure being sent
- More intuitive and maintainable code
- No need to understand `SubstitutionResult` or `SubstitutionValue` abstractions

#### Example 2: Creating Multiple Measurements from an Array

**Using Substitution as JavaScript (Deprecated):**

```javascript
/**
 * Sample to generate multiple measurements
 * payload:
 * {
 *     "temperature": [139.0, 150.0],
 *     "externalId": "berlin_01"
 * }
 * topic: 'testGraalsMulti/berlin_01'
 */
function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    const tempArray = sourceObject['temperature'];

    // Create a new SubstitutionResult with the HashMaps
    const result = new SubstitutionResult();

    // Loop through all temperature array entries
    for (let i = 0; i < tempArray.length; i++) {
        const temperatureValue = new SubstitutionValue(
            tempArray[i],
            TYPE.NUMBER,
            RepairStrategy.DEFAULT,
            true
        );
        addSubstitution(result, 'c8y_TemperatureMeasurement.T.value', temperatureValue);
    }

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}
```

**Migrated to Smart Function:**

```javascript
/**
 * Sample to generate multiple measurements using Smart Function
 * payload:
 * {
 *     "temperature": [139.0, 150.0],
 *     "externalId": "berlin_01"
 * }
 * topic: 'testGraalsMulti/berlin_01'
 */
function onMessage(inputMsg, context) {
    const msg = inputMsg;
    const payload = JSON.parse(msg.getPayload());

    // Extract device identifier from topic
    const topicLevels = msg.getTopic().split('/');
    const deviceId = topicLevels[1]; // 'berlin_01'

    const tempArray = payload.temperature;
    const results = [];

    // Create a separate measurement for each temperature value
    for (let i = 0; i < tempArray.length; i++) {
        results.push({
            cumulocityType: "measurement",
            action: "create",
            payload: {
                "time": new Date().toISOString(),
                "type": "c8y_TemperatureMeasurement",
                "c8y_TemperatureMeasurement": {
                    "T": {
                        "value": tempArray[i],
                        "unit": "C"
                    }
                }
            },
            externalSource: [{
                "type": "c8y_Serial",
                "externalId": deviceId
            }]
        });
    }

    return results;
}
```

**Key Improvements:**
- Each measurement is explicitly created as a separate object in the results array
- No need for `expandArray` flags or understanding substitution semantics
- Clear loop that directly constructs the measurement payloads
- Easy to understand that multiple measurements will be created

#### Example 3: Overriding the Target API

**Using Substitution as JavaScript (Deprecated):**

```javascript
/**
 * Snippet shows overriding API: ALARM, EVENT, MEASUREMENT, ...
 */
function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Override API to send to ALARMS endpoint instead of default
    const api = new SubstitutionValue('ALARMS', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, '_CONTEXT_DATA_.api', api);

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}
```

**Migrated to Smart Function:**

```javascript
/**
 * Snippet shows creating different API types: alarm, event, measurement, ...
 * payload:
 * {
 *     "severity": "MAJOR",
 *     "text": "Temperature threshold exceeded",
 *     "externalId": "berlin_01"
 * }
 */
function onMessage(inputMsg, context) {
    const msg = inputMsg;
    const payload = JSON.parse(msg.getPayload());

    // Extract device identifier from topic
    const topicLevels = msg.getTopic().split('/');
    const deviceId = topicLevels[1]; // 'berlin_01'

    // Simply specify the cumulocityType - no need to override context data!
    return [{
        cumulocityType: "alarm",  // Can be "alarm", "event", "measurement", "inventory"
        action: "create",
        payload: {
            "time": new Date().toISOString(),
            "type": "c8y_TemperatureAlarm",
            "severity": payload.severity,
            "text": payload.text,
            "status": "ACTIVE"
        },
        externalSource: [{
            "type": "c8y_Serial",
            "externalId": deviceId
        }]
    }];
}
```

**Key Improvements:**
- No need to manipulate `_CONTEXT_DATA_.api` - just set the `cumulocityType` field
- Direct and explicit specification of the target API type
- Clear payload structure for the alarm (or event, measurement, etc.)
- Easy to switch between different API types by changing `cumulocityType`

## Shortcomings

Despite its capabilities, Substitution as JavaScript had several significant limitations:

### 1. Unintuitive Approach

The creation of substitution objects in JavaScript felt artificial and was not intuitive for developers. Instead of focusing on the actual payload transformation, developers had to:

- Manually construct substitution objects with specific properties (`pathSource`, `pathTarget`, `repairStrategy`, `expandArray`)
- Understand the internal substitution model
- Work with an abstraction layer that added complexity rather than simplifying the task

### 2. Indirect Payload Definition

Developers had to think in terms of "how to create substitutions" rather than "what the resulting payload should look like," which created unnecessary cognitive overhead.

## Migration to Smart Functions

**Smart Functions** address all the limitations of Substitution as JavaScript while providing a more powerful and intuitive approach to payload transformation.

### Key Advantages of Smart Functions

1. **Direct Payload Creation**: Smart Functions concentrate on the resulting target payload rather than creating substitution objects
2. **Intuitive Syntax**: Write JavaScript that directly constructs the Cumulocity API payload
3. **Full Visibility**: See exactly what payload will be sent to the Cumulocity backend
4. **Enhanced Capabilities**: Access to context, device lookup, and enrichment functions
5. **Better Maintainability**: Code is more readable and easier to maintain

### Smart Function Example

```javascript
function onMessage(inputMsg, context) {
    const msg = inputMsg;
    var payload = msg.getPayload();

    console.log("Processing message:", payload.get('messageId'));

    // Direct payload creation - no substitution objects needed
    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": new Date().toISOString(),
            "type": "c8y_TemperatureMeasurement",
            "c8y_TemperatureMeasurement": {
                "T": {
                    "unit": "C",
                    "value": payload.get("temperature")
                }
            }
        },
        externalSource: [{
            "type": "c8y_Serial",
            "externalId": payload.get("deviceId")
        }]
    }];
}
```

## Migration Guide

### Step 1: Identify Mappings Using Substitution as JavaScript

Mappings using Substitution as JavaScript are marked as:
- **Transformation Type**: `SUBSTITUTION_AS_CODE`
- **Label in UI**: "Substitution as JavaScript (deprecated)"

### Step 2: Understand the Existing Logic

Review your existing Substitution as JavaScript code and identify:
- What substitutions are being created
- What conditions determine the substitutions
- What the final payload structure should be

### Step 3: Convert to Smart Function

Create a Smart Function that:
1. Accepts the `onMessage(inputMsg, context)` signature
2. Extracts data from the payload using `msg.getPayload()`
3. Directly constructs and returns the target payload array

### Step 4: Test Thoroughly

Use the mapping test functionality to:
- Verify the payload structure matches expectations
- Test edge cases and conditional logic
- Validate device identification and enrichment

## Comparison Table

| Feature | Substitution as JavaScript | Smart Functions |
|---------|---------------------------|-----------------|
| **Approach** | Create substitution objects | Create target payload directly |
| **Intuitiveness** | Low - requires understanding substitution model | High - direct payload construction |
| **Control Structures** | Yes (if, for, while) | Yes (if, for, while) |
| **Dynamic API Selection** | Yes | Yes |
| **Payload Visibility** | Indirect - through substitutions | Direct - see exact payload |
| **Device Enrichment** | Limited | Full context and lookup capabilities |
| **Code Readability** | Lower - abstraction overhead | Higher - clear payload structure |
| **Future Support** | Deprecated - will be removed | Actively maintained and enhanced |

## Timeline

- **Release 6.1.5**: Substitution as JavaScript marked as deprecated
- **Future Release**: Feature will be removed completely

We recommend migrating all mappings using Substitution as JavaScript to Smart Functions as soon as possible to ensure continued compatibility with future releases.

## Additional Resources

- [User Guide - Smart Functions](/USERGUIDE.md#defining-the-payload-transformation-using-a-smart-function-javascript)
- [Architecture Overview](/ARCHITECTURE.md)
- [Extensions Guide](/EXTENSIONS.md)

## Support

For questions or assistance with migration, please refer to the project documentation or open an issue in the repository.

---

*This deprecation notice is part of the Cumulocity Dynamic Mapper project.*
