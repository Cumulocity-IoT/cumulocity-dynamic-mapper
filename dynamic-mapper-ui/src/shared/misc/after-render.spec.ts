/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { ApplicationRef, Injector, NgZone } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterNextRenderInZone } from './after-render';

describe('afterNextRenderInZone', () => {
  let injector: Injector;
  let appRef: ApplicationRef;

  beforeEach(() => {
    injector = TestBed.inject(Injector);
    appRef = TestBed.inject(ApplicationRef);
  });

  it('does not run before a render, then runs once after it', () => {
    let calls = 0;

    afterNextRenderInZone(injector, () => calls++);
    expect(calls).toBe(0);

    appRef.tick();
    expect(calls).toBe(1);

    appRef.tick();
    expect(calls).toBe(1);
  });

  it('runs inside the Angular zone, so change detection follows what it changes', () => {
    let inZone: boolean | undefined;

    afterNextRenderInZone(injector, () => (inZone = NgZone.isInAngularZone()));
    appRef.tick();

    expect(inZone).toBeTrue();
  });
});
