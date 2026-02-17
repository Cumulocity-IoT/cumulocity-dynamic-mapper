# Quick Start Guide - TypeScript Smart Functions

Get up and running with TypeScript Smart Functions in 5 minutes!

## ⚡ Quick Start (5 minutes)

### Step 1: Install Dependencies (1 min)

```bash
cd dynamic-mapper-smart-function
npm install
```

### Step 2: Build TypeScript to JavaScript (30 sec)

```bash
npm run build
```

**What happens:**
- TypeScript files in `src/` are compiled to JavaScript in `dist/`
- Type declaration files (`.d.ts`) are generated
- Source maps are created for debugging

### Step 3: View Compiled JavaScript (30 sec)

```bash
# View the compiled inbound example
cat dist/examples/inbound-basic.js

# View the compiled outbound example
cat dist/examples/outbound-basic.js
```

### Step 4: Use in Dynamic Mapper (2 min)

**Option A: Copy to clipboard**
```bash
# macOS
pbcopy < dist/examples/inbound-basic.js

# Linux (with xclip)
xclip -selection clipboard < dist/examples/inbound-basic.js

# Windows (PowerShell)
Get-Content dist/examples/inbound-basic.js | Set-Clipboard
```

**Option B: Copy file content**
1. Open `dist/examples/inbound-basic.js` in your editor
2. Copy the entire content (Cmd+A, Cmd+C / Ctrl+A, Ctrl+C)
3. Paste into Dynamic Mapper Smart Function editor

### Step 5: Run Tests (1 min)

```bash
npm test
```

## 🎯 Your First TypeScript Smart Function

### Create a new Smart Function

```typescript
// src/examples/my-first-function.ts
import { SmartFunction } from '../types';

const onMessage: SmartFunction = (msg, context) => {
  const payload = msg.getPayload();
  const clientId = context.getClientId();

  console.log('Processing message:', payload);

  return [{
    cumulocityType: "measurement",
    action: "create",
    payload: {
      type: "c8y_MyMeasurement",
      time: new Date().toISOString(),
      c8y_Data: {
        value: {
          value: payload.get("value"),
          unit: payload.get("unit")
        }
      }
    },
    externalSource: [{ type: "c8y_Serial", externalId: clientId! }]
  }];
};

export default onMessage;
```

### Build it

```bash
npm run build
```

### Use it

The compiled JavaScript is now in `dist/examples/my-first-function.js`!

## 🧪 Testing Your Function

### Create a test

```typescript
// src/__tests__/my-first-function.spec.ts
import { onMessage } from '../examples/my-first-function';
import { createMockInputMessage, createMockRuntimeContext } from '../types';

describe('My First Function', () => {
  it('should create a measurement', () => {
    const mockMsg = createMockInputMessage({
      value: 42,
      unit: 'kg'
    });

    const mockContext = createMockRuntimeContext({
      clientId: 'TEST-DEVICE'
    });

    const result = onMessage(mockMsg, mockContext);

    expect(result[0].cumulocityType).toBe('measurement');
    expect(result[0].payload.c8y_Data.value.value).toBe(42);
  });
});
```

### Run tests

```bash
npm test
```

## 🔄 Development Workflow

### During Development

```bash
# Terminal 1: Auto-compile on file changes
npm run build:watch

# Terminal 2: Auto-run tests on file changes
npm run test:watch
```

Now edit your TypeScript files and see:
- ✅ Automatic compilation
- ✅ Automatic test execution
- ✅ Instant feedback

### Before Deployment

```bash
# Run full test suite
npm test

# Build production version
npm run build

# View compiled JavaScript
ls -la dist/examples/
```

## 💡 Common Patterns

### Pattern 1: Access Payload Data

```typescript
const payload = msg.getPayload();

// Object-style access
const temp = payload["temperature"];

// Map-like API
const temp = payload.get("temperature");
```

### Pattern 2: Lookup Device

```typescript
// By device ID
const device = context.getManagedObjectByDeviceId("12345");

// By external ID
const device = context.getManagedObject({
  externalId: "SENSOR-001",
  type: "c8y_Serial"
});
```

### Pattern 3: Use State

```typescript
// Save state
context.setState("lastValue", 42);

// Retrieve state
const lastValue = context.getState("lastValue");

// Get all state
const allState = context.getStateAll();
```

### Pattern 4: Create Measurement

```typescript
return [{
  cumulocityType: "measurement",
  action: "create",
  payload: {
    type: "c8y_TemperatureMeasurement",
    time: new Date().toISOString(),
    c8y_Temperature: {
      T: { value: 25.5, unit: "C" }
    }
  },
  externalSource: [{ type: "c8y_Serial", externalId: clientId }]
}];
```

### Pattern 5: Create Device Message (Outbound)

```typescript
return {
  topic: `device/${deviceId}/data`,
  payload: new TextEncoder().encode(JSON.stringify({
    temperature: 25.5,
    timestamp: Date.now()
  }))
};
```

## 🚀 Next Steps

1. **Explore Examples** - Check out [`src/examples/`](src/examples/) for more patterns
2. **Read Full README** - See [`README.md`](README.md) for complete documentation
3. **Review Type Definitions** - Check [`src/types/smart-function-runtime.types.ts`](src/types/smart-function-runtime.types.ts)
4. **Check Main Documentation** - See [`../SMART_FUNCTION_TYPINGS.md`](../SMART_FUNCTION_TYPINGS.md)

## ❓ FAQ

### Q: How do I know what types to use?

A: Your IDE will suggest types as you type! Just import from `'../types'` and use IntelliSense (Ctrl+Space).

### Q: Can I use external npm packages?

A: For simple functions, avoid external dependencies. If needed, use bundling tools (webpack/rollup) to create a single JavaScript file.

### Q: The compiled JavaScript is very large. How do I reduce it?

A: The compiled JavaScript includes some runtime helpers. You can:
1. Set `"target": "ES2020"` in `tsconfig.json`
2. Use `"module": "ES2015"` for smaller output
3. Remove comments with `"removeComments": true`

### Q: Can I debug the TypeScript in the Dynamic Mapper?

A: The Dynamic Mapper runs JavaScript. For debugging:
1. Run tests locally with full TypeScript support
2. Add console.log statements before building
3. Use source maps to map JavaScript back to TypeScript

### Q: How do I share types across multiple Smart Functions?

A: Create shared type definitions in `src/types/` and import them in your Smart Functions.

## 🎉 You're Ready!

You now have:
- ✅ TypeScript Smart Function project set up
- ✅ Example functions to learn from
- ✅ Tests to validate your code
- ✅ Build system to compile to JavaScript
- ✅ Development workflow for rapid iteration

**Start building your Smart Functions with type safety!** 🚀
