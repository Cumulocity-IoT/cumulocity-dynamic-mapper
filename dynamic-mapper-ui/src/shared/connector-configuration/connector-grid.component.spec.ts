/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { EventEmitter } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';
import { ConnectorGridComponent } from './connector-grid.component';
import { DeploymentMapEntry } from '../mapping/mapping.model';

/**
 * Class-level spec: the component imports CoreModule, which cannot be created in TestBed
 * (NG0201 ApplicationService), so the instance is built without running the constructor.
 */
describe('ConnectorGridComponent deployment map entry', () => {
  let component: ConnectorGridComponent;
  let emitted: DeploymentMapEntry[];

  beforeEach(() => {
    component = Object.create(ConnectorGridComponent.prototype);
    component.readOnly = false;
    component.deploymentMapEntryChange = new EventEmitter<DeploymentMapEntry>();
    emitted = [];
    component.deploymentMapEntryChange.subscribe(e => emitted.push(e));
  });

  it('emits a new entry with the selected connectors and leaves the input untouched', () => {
    const input = { identifier: 'm1', connectors: ['a'] } as DeploymentMapEntry;
    component.deploymentMapEntry = input;

    (component as any).updateDeploymentMapEntry(['a', 'b']);

    expect(emitted.length).toBe(1);
    expect(emitted[0]).not.toBe(input);
    expect(emitted[0]).toEqual({ identifier: 'm1', connectors: ['a', 'b'] } as DeploymentMapEntry);
    expect(input.connectors).toEqual(['a']);
  });

  it('emits nothing without an entry', () => {
    component.deploymentMapEntry = undefined as any;

    (component as any).updateDeploymentMapEntry(['a']);

    expect(emitted).toEqual([]);
  });
});

/**
 * The grid is OnPush. The selected connectors' names are looked up in `configurations`, which is set from a
 * subscription rather than from an event of the grid's own view, so the grid has to mark itself.
 */
describe('ConnectorGridComponent connector list (OnPush)', () => {
  let component: any;
  let markForCheck: jasmine.Spy;
  let list$: BehaviorSubject<any[]>;

  beforeEach(() => {
    markForCheck = jasmine.createSpy('markForCheck');
    list$ = new BehaviorSubject<any[]>([{ identifier: 'a', name: 'Alpha' }]);
    component = Object.create(ConnectorGridComponent.prototype);
    component.cdr = { markForCheck };
    component.selectable = false;
    component.destroy$ = new Subject<void>();
    component.selected$ = new BehaviorSubject<string[]>([]);
    component.configurations$ = list$;
    component.setupConfigurationsSubscription();
  });

  it('marks itself for checking and exposes the names once the list has loaded', () => {
    expect(markForCheck).toHaveBeenCalledTimes(1);
    expect(component.findNameByIdent('a')).toBe('Alpha');
  });

  it('marks itself again when the list changes', () => {
    list$.next([{ identifier: 'a', name: 'Alpha 2' }]);

    expect(markForCheck).toHaveBeenCalledTimes(2);
    expect(component.findNameByIdent('a')).toBe('Alpha 2');
  });
});
