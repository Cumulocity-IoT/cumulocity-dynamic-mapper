/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Public API exports for Smart Function Runtime types.
 *
 * Source of truth: the **Java runtime** — `SmartFunctionContext.java`, `InputMessage.java`,
 * `CumulocityObject.java` and `DeviceMessage.java` in `dynamic-mapper-service`. The declarations
 * in this package are a hand-maintained mirror of what those classes actually expose to
 * JavaScript; see `docs/contract-sync.md` for the checks that keep them aligned.
 *
 * Downstream consumers mirror *this* package, not the other way round:
 * - the shipped JS code templates are type-checked against it (`npm run check:templates`)
 * - the mapping editor's completion provider is being moved onto it
 *
 * (An earlier version of this comment named
 * `dynamic-mapper-ui/src/mapping/core/processor/smart-function-runtime.types.ts` as the
 * authority. That file does not exist, and the direction it described was backwards.)
 */

// Re-exports IDP DataPrep base types (DataPrepContext, ExternalId) and all DM-specific types
export * from './smart-function-dynamic-mapper.types';
