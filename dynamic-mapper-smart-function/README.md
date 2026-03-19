# Cumulocity Dynamic Mapper - Smart Function TypeScript Examples

This project demonstrates how to write **type-safe Smart Functions** for the Cumulocity Dynamic Mapper using TypeScript, and how to compile them to JavaScript for deployment.

## Table of Contents

- [Overview](#overview)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Building TypeScript to JavaScript](#building-typescript-to-javascript)
- [Examples](#examples)
- [Testing](#testing)
- [Development Workflow](#development-workflow)
- [Deployment](#deployment)

## Overview

This project provides:

- ✅ **Complete TypeScript type definitions** for Smart Function Runtime API
  - Types are maintained in `src/types/` within this project
  - `dataprep.types.ts` — IDP DataPrep base types (standard Cumulocity)
  - `smart-function-dynamic-mapper.types.ts` — Dynamic Mapper extensions
- ✅ **Example Smart Functions** (inbound and outbound) in TypeScript
- ✅ **Unit tests** demonstrating how to test Smart Functions
- ✅ **Build configuration** to compile TypeScript to JavaScript
- ✅ **Mock helpers** for local testing

### Type Organization

Type definitions are split across two files:

```
src/types/
├── dataprep.types.ts                       # IDP DataPrep base types (DataPrepContext, ExternalId)
├── smart-function-dynamic-mapper.types.ts  # Dynamic Mapper extensions + all exports
└── index.ts                                # Re-exports everything
```

Import all types from `'../types'` (the `index.ts` barrel):

```typescript
import { SmartFunctionIn, DynamicMapperDeviceMessage, SmartFunctionContext } from '../types';
```

## Project Structure

```
dynamic-mapper-smart-function/
├── src/
│   ├── types/                          # Type definitions
│   │   ├── dataprep.types.ts           # IDP DataPrep base types
│   │   ├── smart-function-dynamic-mapper.types.ts  # DM-specific types + helpers
│   │   └── index.ts                    # Type exports
│   ├── examples/                       # Example Smart Functions
│   │   ├── inbound-basic.ts           # Basic inbound example
│   │   ├── inbound-enrichment.ts      # Inbound with device lookup
│   │   ├── inbound-with-state.ts      # Inbound with state management
│   │   ├── inbound-typed.ts           # Typed inbound + outbound examples
│   │   ├── outbound-basic.ts          # Basic outbound example
│   │   └── outbound-with-transformation.ts  # Outbound with transformation
│   ├── __tests__/                      # Unit tests
│   │   ├── inbound-basic.spec.ts
│   │   ├── inbound-enrichment.spec.ts
│   │   └── outbound-basic.spec.ts
│   └── index.ts                        # Main entry point
├── dist/                               # Compiled JavaScript output
├── package.json                        # Project configuration
├── tsconfig.json                       # TypeScript configuration
├── jest.config.js                      # Jest test configuration
└── README.md                           # This file
```

## Getting Started

### Prerequisites

- Node.js >= 18.0.0
- npm >= 9.0.0

### Installation

```bash
# Navigate to the project directory
cd dynamic-mapper-smart-function

# Install dependencies
npm install
```

## Building TypeScript to JavaScript

### Compile Once

```bash
npm run build
```

This uses `zshy` to compile TypeScript to JavaScript and generate declaration files.

**Output:**
```
dist/
├── types/
│   ├── dataprep.types.js
│   ├── smart-function-dynamic-mapper.types.js
│   └── index.js
├── examples/
│   ├── inbound-basic.js               ← Use this in Dynamic Mapper!
│   ├── inbound-enrichment.js
│   ├── inbound-with-state.js
│   ├── inbound-typed.js
│   ├── outbound-basic.js              ← Use this in Dynamic Mapper!
│   └── outbound-with-transformation.js
└── index.js
```

### Using Compiled JavaScript

After building, the compiled JavaScript files in `dist/examples/` can be used directly in the Dynamic Mapper:

1. **Copy the compiled JavaScript:**
   ```bash
   # Copy inbound example
   cp dist/examples/inbound-basic.js /path/to/dynamic-mapper/smart-functions/

   # Copy outbound example
   cp dist/examples/outbound-basic.js /path/to/dynamic-mapper/smart-functions/
   ```

2. **Or copy the content** and paste it into the Dynamic Mapper UI Smart Function editor.

## Examples

### Example 1: Basic Inbound Smart Function

**TypeScript:** [`src/examples/inbound-basic.ts`](src/examples/inbound-basic.ts)

```typescript
import {
  SmartFunctionIn,
  DynamicMapperDeviceMessage,
  SmartFunctionContext,
  CumulocityObject,
} from '../types';

const onMessage: SmartFunctionIn = (
  msg: DynamicMapperDeviceMessage,
  context: SmartFunctionContext
): CumulocityObject[] => {
  const payload = msg.payload;
  const clientId = context.getClientId() || payload['clientId'];

  return [{
    cumulocityType: "measurement",
    action: "create",
    payload: {
      time: new Date().toISOString(),
      type: "c8y_TemperatureMeasurement",
      c8y_Steam: {
        Temperature: {
          unit: "C",
          value: payload["sensorData"]["temp_val"]
        }
      }
    },
    externalSource: [{ type: "c8y_Serial", externalId: clientId! }]
  }];
};

export default onMessage;
```

**Compiled JavaScript:** `dist/examples/inbound-basic.js`

### Example 2: Inbound with Device Enrichment

**TypeScript:** [`src/examples/inbound-enrichment.ts`](src/examples/inbound-enrichment.ts)

Shows how to:
- Look up devices using `getManagedObject()` and `getManagedObjectByExternalId()`
- Create different measurements based on device configuration
- Handle missing devices gracefully

### Example 3: Inbound with State Management

**TypeScript:** [`src/examples/inbound-with-state.ts`](src/examples/inbound-with-state.ts)

Demonstrates:
- Using `setState()` and `getState()`
- Tracking statistics across invocations (min/max/count)
- State persists per mapping across messages, cleared on mapping deletion

### Example 4: Typed Smart Functions

**TypeScript:** [`src/examples/inbound-typed.ts`](src/examples/inbound-typed.ts)

Demonstrates the generic type parameters on `SmartFunctionIn<T>` and `SmartFunctionOut<T>`:
- `SmartFunctionIn<'managedObject' | 'event'>` — restricts allowed return types at compile time
- `SmartFunctionOut<'measurement'>` — narrows `msg.cumulocityType` in outbound functions

### Example 5: Basic Outbound Smart Function

**TypeScript:** [`src/examples/outbound-basic.ts`](src/examples/outbound-basic.ts)

Shows how to:
- Access a Cumulocity payload (pre-deserialized `SmartFunctionPayload`)
- Convert data and encode the result as `Uint8Array`
- Use the `_externalId_` topic placeholder

### Example 6: Outbound with Data Transformation

**TypeScript:** [`src/examples/outbound-with-transformation.ts`](src/examples/outbound-with-transformation.ts)

Demonstrates:
- Complex data transformation
- Custom device payload formats
- Using Kafka `transportFields`

## Testing

### Run All Tests

```bash
npm test
```

### Run Tests in Watch Mode

```bash
npm run test:watch
```

### Example Test

```typescript
import { onMessage } from '../examples/inbound-basic';
import { createMockInputMessage, createMockRuntimeContext, CumulocityObject } from '../types';

describe('Inbound Basic Smart Function', () => {
  it('should create a temperature measurement', () => {
    // Arrange
    const mockMsg = createMockInputMessage({
      sensorData: { temp_val: 25.5 },
      clientId: 'SENSOR-001'
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'SENSOR-001'
    });

    // Act
    const result = onMessage(mockMsg, mockContext);

    // Assert
    expect(Array.isArray(result)).toBe(true);
    const action = (result as CumulocityObject[])[0];
    expect(action.cumulocityType).toBe('measurement');
    expect((action.payload as any).c8y_Steam.Temperature.value).toBe(25.5);
  });
});
```

## Development Workflow

### 1. Write Smart Function in TypeScript

Create a new file in `src/examples/`:

```typescript
// src/examples/my-smart-function.ts
import { SmartFunctionIn } from '../types';

const onMessage: SmartFunctionIn = (msg, context) => {
  // Your logic here with full type safety and IntelliSense!
  const payload = msg.payload;
  // ...
  return [];
};

export default onMessage;
```

### 2. Write Tests

Create test file in `src/__tests__/`:

```typescript
// src/__tests__/my-smart-function.spec.ts
import { onMessage } from '../examples/my-smart-function';
import { createMockInputMessage, createMockRuntimeContext } from '../types';

describe('My Smart Function', () => {
  it('should work correctly', () => {
    const mockMsg = createMockInputMessage({ /* ... */ });
    const mockContext = createMockRuntimeContext({ /* ... */ });

    const result = onMessage(mockMsg, mockContext);

    expect(result).toBeDefined();
  });
});
```

### 3. Run Tests

```bash
npm test
```

### 4. Build to JavaScript

```bash
npm run build
```

### 5. Deploy to Dynamic Mapper

Copy the compiled JavaScript from `dist/examples/my-smart-function.js` to the Dynamic Mapper.

## Deployment

### Option 1: Copy Compiled JavaScript

After building, copy the JavaScript file:

```bash
# View the compiled JavaScript
cat dist/examples/inbound-basic.js

# Copy to clipboard (macOS)
pbcopy < dist/examples/inbound-basic.js

# Copy to clipboard (Linux with xclip)
xclip -selection clipboard < dist/examples/inbound-basic.js
```

Then paste into the Dynamic Mapper UI Smart Function editor.

### Option 2: Direct File Upload

If the Dynamic Mapper supports file upload:

1. Build the project: `npm run build`
2. Navigate to `dist/examples/`
3. Upload the desired `.js` file

## Learning Resources

### TypeScript Benefits

**Before (JavaScript):**
```javascript
function onMessage(msg, context) {
  const payload = msg.payload;
  // No autocomplete, no type checking
  const device = context.getManagedObjectByExternalId({ ... }); // Typo not caught!
}
```

**After (TypeScript):**
```typescript
const onMessage: SmartFunctionIn = (msg, context) => {
  const payload = msg.payload;  // ✅ Autocomplete!
  const device = context.getManagedObjectByExternalId({ ... }); // ❌ Compile error if wrong!
};
```

### Available Types

**Smart Function signatures:**
- **`SmartFunctionIn<T>`** — Inbound function: device message → Cumulocity objects
- **`SmartFunctionOut<T>`** — Outbound function: Cumulocity event → device messages
- **`SmartFunction`** — Union of `SmartFunctionIn | SmartFunctionOut`

**Message types:**
- **`DynamicMapperDeviceMessage`** — Inbound message with pre-deserialized `payload`
- **`OutboundMessage<T>`** — Outbound trigger from Cumulocity (pre-deserialized payload)
- **`SmartFunctionPayload`** — Payload with bracket-notation and `.get()` access

**Context:**
- **`SmartFunctionContext`** — Runtime context: state, config, device lookups
- **`DataPrepContext`** — IDP base context (`getState` / `setState`)

**Output types:**
- **`CumulocityObject<T>`** — Inbound action (create/update/delete in C8Y)
- **`DeviceMessage<T>`** — Outbound message to broker

**Domain objects:**
- **`C8yMeasurement`**, **`C8yEvent`**, **`C8yAlarm`**, **`C8yOperation`**, **`C8yManagedObject`**

**Identifiers:**
- **`ExternalId`** — `{ externalId, type }` for device lookup
- **`ExternalSource`** — Extended external ID with `autoCreateDeviceMO`, `parentId`, etc.

### Mock Helpers

- **`createMockPayload(data)`** — Create mock `SmartFunctionPayload`
- **`createMockInputMessage(data, topic?, clientId?)`** — Create mock `DynamicMapperDeviceMessage`
- **`createMockOutboundMessage(data, cumulocityType?, sourceId?)`** — Create mock `OutboundMessage`
- **`createMockRuntimeContext(options)`** — Create mock `SmartFunctionContext`

## Troubleshooting

### Build Errors

**Error: Cannot find module '../types'**

Make sure you're importing from the correct path:
```typescript
import { SmartFunctionIn } from '../types';  // ✅ Correct
import { SmartFunctionIn } from './types';   // ❌ Wrong
```

**Error: Type 'X' is not assignable to type 'Y'**

Check the type definitions and ensure your data matches the expected types.

### Test Errors

**Error: Cannot find module 'ts-jest'**

Install dependencies:
```bash
npm install
```

### Runtime Errors in Dynamic Mapper

**The compiled JavaScript uses features not supported in the Smart Function runtime:**

Adjust TypeScript target in `tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020"  // Or "ES2015" for older runtimes
  }
}
```

## License

Apache-2.0

## Contributing

Contributions are welcome! Please:

1. Write tests for new features
2. Follow the existing code style
3. Update documentation as needed

## Support

For questions or issues:

- Check the [User Guide](../USERGUIDE.md)
- Open an issue in the GitHub repository
