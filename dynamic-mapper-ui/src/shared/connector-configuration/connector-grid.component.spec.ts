/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { EventEmitter } from '@angular/core';
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
