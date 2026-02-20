# Type Management Strategy

## 📋 Overview

The Smart Function Runtime type definitions are maintained in a **single location** to prevent duplication and ensure consistency across the project.

## 🎯 Single Source of Truth

**Primary Location (Authoritative):**
```
dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts
```

This file contains:
- Complete Smart Function Runtime API type definitions
- Mock helper functions for testing
- JSDoc documentation
- All type exports

## 🔗 How the Sample Project References Types

The sample project **imports from the UI project** instead of duplicating the types:

```typescript
// dynamic-mapper-smart-function/src/types/index.ts
export * from '../../../dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types';
```

### Benefits

✅ **No Duplication** - Types exist in only one place
✅ **Always in Sync** - Changes to types automatically propagate
✅ **Easier Maintenance** - Update once, use everywhere
✅ **Single Source of Truth** - UI project is the authoritative source

## 📁 Project Structure

```
dynamic-mapper/
├── dynamic-mapper-ui/
│   └── src/mapping/core/processor/
│       └── smart-function-runtime.types.ts  ← SINGLE SOURCE OF TRUTH
│
└── dynamic-mapper-smart-function/
    └── src/types/
        └── index.ts  ← Re-exports from UI project
```

## 🔨 Configuration

### TypeScript Configuration

The sample project's `tsconfig.json` is configured to allow importing from outside the project:

```json
{
  "compilerOptions": {
    "module": "commonjs",
    "moduleResolution": "node",
    "baseUrl": "./",
    // Note: NO rootDir constraint - allows importing from sibling projects
    "outDir": "./dist"
  },
  "include": ["src/**/*"]
}
```

**Key Point:** We removed the `rootDir` constraint to allow importing from the UI project.

## 📝 How to Update Types

### To Add/Modify Types:

1. **Edit the UI project types:**
   ```bash
   code dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts
   ```

2. **Changes automatically available to sample project:**
   - No need to copy files
   - No need to synchronize
   - Just rebuild: `npm run build`

3. **Rebuild sample project:**
   ```bash
   cd dynamic-mapper-smart-function
   npm run build
   ```

### Example: Adding a New Type

**Step 1: Edit UI project types**
```typescript
// dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts

// Add new type
export interface C8yCustomObject {
  id?: string;
  customField: string;
}
```

**Step 2: Use immediately in sample project**
```typescript
// dynamic-mapper-smart-function/src/examples/my-example.ts
import { SmartFunction, C8yCustomObject } from '../types';

const onMessage: SmartFunction = (msg, context) => {
  const customObj: C8yCustomObject = {
    customField: 'value'
  };
  // ...
};
```

**Step 3: Rebuild**
```bash
cd dynamic-mapper-smart-function
npm run build
```

Done! ✅

## 🧪 Testing Impact

Tests automatically use the latest types from the UI project:

```typescript
// Tests import from '../types', which references UI project
import { createMockInputMessage, createMockRuntimeContext } from '../types';

// Always uses latest type definitions
const mockMsg = createMockInputMessage({ temperature: 25.5 });
```

## 🚀 Deployment

When deploying the sample project:

1. **Types are compiled into the JavaScript:**
   - Type information is stripped (TypeScript → JavaScript)
   - No runtime dependency on UI project

2. **Compiled JavaScript is standalone:**
   ```bash
   dist/examples/inbound-basic.js  # Standalone, no dependencies
   ```

3. **Can be deployed independently:**
   - Copy JavaScript to Dynamic Mapper
   - No need to include UI project

## 🔄 Workflow

### Development Workflow

```bash
# Terminal 1: Work on UI types
cd dynamic-mapper-ui
code src/mapping/core/processor/smart-function-runtime.types.ts

# Terminal 2: Auto-rebuild sample project
cd dynamic-mapper-smart-function
npm run build:watch

# Terminal 3: Auto-run tests
cd dynamic-mapper-smart-function
npm run test:watch
```

### When Types Change

1. Edit `dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts`
2. Sample project automatically picks up changes on next build
3. Tests validate the changes
4. Deploy updated compiled JavaScript

## 📊 Comparison: Before vs After

### Before (Duplicated Types) ❌

```
dynamic-mapper-ui/
└── src/mapping/core/processor/
    └── smart-function-runtime.types.ts  (1000 lines)

dynamic-mapper-smart-function/
└── src/types/
    └── smart-function-runtime.types.ts  (1000 lines) ← DUPLICATE!
```

**Problems:**
- ❌ 2000 lines total (duplicated)
- ❌ Types can get out of sync
- ❌ Must update in two places
- ❌ Risk of inconsistency

### After (Single Source) ✅

```
dynamic-mapper-ui/
└── src/mapping/core/processor/
    └── smart-function-runtime.types.ts  (1000 lines) ← SINGLE SOURCE

dynamic-mapper-smart-function/
└── src/types/
    └── index.ts  (10 lines - just re-exports)
```

**Benefits:**
- ✅ 1010 lines total (99% reduction in duplication)
- ✅ Types always in sync
- ✅ Update once, use everywhere
- ✅ No risk of inconsistency

## 🛠️ Troubleshooting

### Issue: Build fails with "File is not under 'rootDir'"

**Solution:** Ensure `rootDir` is removed from `tsconfig.json`:

```json
{
  "compilerOptions": {
    // "rootDir": "./src",  ← Remove this line
    "outDir": "./dist"
  }
}
```

### Issue: Types not found

**Solution:** Verify the relative path is correct:

```typescript
// Correct (from dynamic-mapper-smart-function/src/types/)
export * from '../../../dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types';
```

### Issue: Changes to types not reflected

**Solution:** Rebuild the sample project:

```bash
cd dynamic-mapper-smart-function
npm run clean
npm run build
```

## 📚 Related Documentation

- **Type Definitions:** [`smart-function-runtime.types.ts`](../dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts)
- **Usage Examples:** [`EXAMPLES.md`](EXAMPLES.md)
- **Quick Start:** [`QUICK_START.md`](QUICK_START.md)

## ✅ Best Practices

1. **Always edit types in UI project** - Never edit in sample project
2. **Rebuild after type changes** - Ensure changes are compiled
3. **Run tests after changes** - Validate type changes work correctly
4. **Document type changes** - Update JSDoc comments in UI project

---

**Last Updated:** 2025-02-17
**Maintained By:** Cumulocity Dynamic Mapper Team
