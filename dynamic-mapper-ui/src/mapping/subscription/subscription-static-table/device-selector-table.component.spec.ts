/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { EventEmitter } from '@angular/core';
import { IIdentified } from '@c8y/client';
import { DeviceSelectorTableComponent } from './device-selector-table.component';

/** Class-level spec: the component imports CoreModule, which TestBed cannot create (NG0201). */
describe('DeviceSelectorTableComponent', () => {
  let component: DeviceSelectorTableComponent;
  let committed: IIdentified[][];

  beforeEach(() => {
    component = Object.create(DeviceSelectorTableComponent.prototype);
    component.commit = new EventEmitter<IIdentified[]>();
    committed = [];
    component.commit.subscribe(list => committed.push(list));
  });

  it('commits the subscribed devices plus the newly selected ones, without duplicates', () => {
    component.deviceList = [{ id: '1' }, { id: '2' }];
    component.selectedItemIds = ['2', '3'];

    component.clickedUpdateSubscription();

    expect(committed).toEqual([[{ id: '1' }, { id: '2' }, { id: '3' }]]);
  });

  it('does not change the list it was given, so a failed save leaves the host unchanged', () => {
    const input: IIdentified[] = [{ id: '1' }];
    component.deviceList = input;
    component.selectedItemIds = ['9'];

    component.clickedUpdateSubscription();

    expect(input).toEqual([{ id: '1' }]);
    expect(committed[0]).not.toBe(input);
  });

  it('commits the unchanged list when nothing is selected', () => {
    component.deviceList = [{ id: '1' }];
    component.selectedItemIds = undefined as any;

    component.clickedUpdateSubscription();

    expect(committed).toEqual([[{ id: '1' }]]);
  });
});
