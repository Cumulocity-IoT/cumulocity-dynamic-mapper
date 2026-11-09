/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { afterNextRender, Injector, NgZone } from '@angular/core';

/**
 * Runs `fn` once, after Angular has rendered the current change detection pass, so the template's
 * bindings, `@ViewChild` references and DOM are up to date. Use it instead of `setTimeout` or
 * `queueMicrotask` when the code needs the view to be rendered first (measuring or scrolling an
 * element, calling a child component, decorating `[innerHTML]` content).
 *
 * `afterNextRender` callbacks run outside the Angular zone. `fn` is run back inside it, so what it
 * changes is followed by change detection, as it was after a `setTimeout`.
 */
export function afterNextRenderInZone(injector: Injector, fn: () => void): void {
  const zone = injector.get(NgZone);
  afterNextRender(() => zone.run(fn), { injector });
}
