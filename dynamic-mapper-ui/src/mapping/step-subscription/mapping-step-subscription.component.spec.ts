/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AlertService } from '@c8y/ngx-components';
import { MappingStepSubscriptionComponent } from './mapping-step-subscription.component';
import { SubscriptionSelectionService } from '../core/subscription-selection.service';
import { emptySelection, hasPendingSubscriptionChanges, OutboundSubscriptionSelection } from './outbound-subscription-selection.model';

@Pipe({ name: 'translate', standalone: true })
class TranslateStubPipe implements PipeTransform {
  transform(value: string): string { return value; }
}

/**
 * The c8y CoreModule reaches into the app-shell DI graph, so, as in the other component specs,
 * it is dropped via overrideComponent. The real template is kept to assert on what is rendered;
 * the drawers and the c8y list components are inert elements here.
 */
describe('MappingStepSubscriptionComponent', () => {
  let fixture: ComponentFixture<MappingStepSubscriptionComponent>;
  let component: MappingStepSubscriptionComponent;
  let selectionService: jasmine.SpyObj<SubscriptionSelectionService>;
  let alerts: jasmine.SpyObj<AlertService>;
  let emitted: OutboundSubscriptionSelection[];

  const baseline = {
    available: true,
    types: ['c8y_Old'],
    groups: [{ id: 'g1', name: 'Phones' }],
    devices: [{ id: 'd1', name: 'iPhone' }]
  };
  const admin = { userHasMappingAdminRole: true, userHasMappingCreateRole: false } as any;

  async function create(selection: OutboundSubscriptionSelection = emptySelection(), feature: any = admin) {
    fixture = TestBed.createComponent(MappingStepSubscriptionComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('selection', selection);
    fixture.componentRef.setInput('feature', feature);
    emitted = [];
    component.selectionChange.subscribe(s => {
      emitted.push(s);
      // The host owns the selection and hands it back, as the editors do.
      fixture.componentRef.setInput('selection', s);
    });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (cy: string) => fixture.nativeElement.querySelector(`[data-cy="${cy}"]`) as HTMLElement | null;

  beforeEach(() => {
    selectionService = jasmine.createSpyObj('SubscriptionSelectionService', ['loadBaseline']);
    selectionService.loadBaseline.and.resolveTo(baseline);
    alerts = jasmine.createSpyObj('AlertService', ['danger', 'warning', 'add']);

    TestBed.overrideComponent(MappingStepSubscriptionComponent, {
      set: { imports: [CommonModule, TranslateStubPipe], schemas: [NO_ERRORS_SCHEMA] }
    });
    TestBed.configureTestingModule({
      providers: [
        { provide: SubscriptionSelectionService, useValue: selectionService },
        { provide: AlertService, useValue: alerts }
      ]
    });
  });

  describe('loading', () => {
    it('loads the current subscriptions once and hands them to the host', async () => {
      await create();
      expect(selectionService.loadBaseline).toHaveBeenCalledTimes(1);
      expect(emitted.at(-1)!.baseline).toEqual(baseline);
      expect(component.ready).toBe(true);
    });

    it('does not reload when the host already holds a baseline (tab switched back)', async () => {
      await create({ ...emptySelection(), baseline });
      expect(selectionService.loadBaseline).not.toHaveBeenCalled();
    });

    it('stays unusable and says so when loading fails, so a replace-style write cannot wipe the list', async () => {
      selectionService.loadBaseline.and.rejectWith(new Error('down'));
      await create();
      expect(component.ready).toBe(false);
      expect(component.actionsDisabled).toBe(true);
      expect(alerts.danger).toHaveBeenCalled();
      expect(emitted).toEqual([]);
    });

    it('shows nothing to do when outbound mappings are disabled', async () => {
      selectionService.loadBaseline.and.resolveTo({ ...baseline, available: false });
      await create();
      expect(component.unavailable).toBe(true);
      expect(component.ready).toBe(false);
    });
  });

  describe('modes', () => {
    it('offers skip, dynamic and static, with skip selected by default', async () => {
      await create();
      expect(q('dm-mapping-stepper-subscription-skip')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-dynamic')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-static')).not.toBeNull();
      expect(component.selection.mode).toBe('skip');
      expect(q('dm-mapping-stepper-subscription-dynamic-panel')).toBeNull();
      expect(q('dm-mapping-stepper-subscription-static-panel')).toBeNull();
    });

    it('shows the type and group actions for dynamic', async () => {
      await create();
      component.setMode('dynamic');
      fixture.detectChanges();
      expect(q('dm-mapping-stepper-subscription-manage-by-type')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-manage-by-group')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-manage-device-selection')).toBeNull();
    });

    it('shows the device selection and table actions for static', async () => {
      await create();
      component.setMode('static');
      fixture.detectChanges();
      expect(q('dm-mapping-stepper-subscription-manage-device-selection')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-manage-device-table')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-manage-by-type')).toBeNull();
    });

    it('disables the actions without the Mapping Admin or Mapping Create role', async () => {
      await create(emptySelection(), { userHasMappingAdminRole: false, userHasMappingCreateRole: false });
      component.setMode('dynamic');
      fixture.detectChanges();
      expect(component.actionsDisabled).toBe(true);
      expect((q('dm-mapping-stepper-subscription-manage-by-type') as HTMLButtonElement).disabled).toBe(true);
    });

    it('uses the data-cy prefix of its host', async () => {
      await create();
      fixture.componentRef.setInput('dataCyPrefix', 'dm-unified-editor');
      fixture.detectChanges();
      expect(q('dm-unified-editor-subscription-skip')).not.toBeNull();
    });
  });

  describe('selector drawers', () => {
    beforeEach(async () => {
      await create();
      component.setMode('dynamic');
    });

    it('collects the type list without writing anything and closes the drawer', () => {
      component.showTypeSelector = true;
      component.onCommitTypes(['c8y_Old', 'c8y_New']);
      expect(component.showTypeSelector).toBe(false);
      expect(emitted.at(-1)!.types).toEqual(['c8y_Old', 'c8y_New']);
      expect(selectionService.loadBaseline).toHaveBeenCalledTimes(1);
    });

    it('collects groups and devices', () => {
      component.onCommitGroups([{ id: 'g2', name: 'Tablets' } as any]);
      expect(emitted.at(-1)!.groups).toEqual([{ id: 'g2', name: 'Tablets' } as any]);

      component.showDeviceTree = true;
      component.showDeviceTable = true;
      component.onCommitDevices([{ id: 'd2' }]);
      expect(component.showDeviceTree).toBe(false);
      expect(component.showDeviceTable).toBe(false);
      expect(emitted.at(-1)!.devices).toEqual([{ id: 'd2' } as any]);
    });

    it('shows pending and removed entries', () => {
      component.onCommitTypes(['c8y_New']);
      expect(component.typesPending).toBe(true);
      expect(component.removedTypes).toEqual(['c8y_Old']);

      component.onCommitGroups([]);
      expect(component.removedGroups.map(g => g.id)).toEqual(['g1']);

      component.setMode('static');
      component.onCommitDevices([]);
      expect(component.removedDevicesCount).toBe(1);
    });

    it('lists at most five devices and counts the rest', () => {
      component.setMode('static');
      component.onCommitDevices(Array.from({ length: 8 }, (_, i) => ({ id: String(i) })));
      expect(component.listedDevices.length).toBe(5);
      expect(component.moreDevices).toBe(3);
    });
  });

  describe('resync option', () => {
    beforeEach(async () => {
      await create();
      component.setMode('dynamic');
    });

    it('is only possible once a new type is in the selection', () => {
      expect(component.canResync).toBe(false);
      component.onCommitTypes(['c8y_Old']);
      expect(component.canResync).toBe(false);
      component.onCommitTypes(['c8y_Old', 'c8y_New']);
      expect(component.canResync).toBe(true);
    });

    it('is off by default and can be switched on', () => {
      expect(component.selection.resyncNewTypes).toBe(false);
      component.setResync(true);
      expect(emitted.at(-1)!.resyncNewTypes).toBe(true);
    });
  });

  describe('suggestions from the Message Explorer', () => {
    beforeEach(async () => {
      await create();
      fixture.componentRef.setInput('suggestions', {
        deviceId: 'dev1',
        deviceName: 'iPhone 15',
        deviceType: 'c8y_SensorPhone',
        groups: [{ id: 'g1', name: 'Phones' }, { id: 'g9', name: 'Lab' }]
      });
      fixture.detectChanges();
    });

    it('shows the device values as radio choices, not the Dynamic/Static panels', () => {
      expect(component.contextMode).toBe(true);
      expect(q('dm-mapping-stepper-subscription-suggestions')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-dynamic')).toBeNull();
      expect(q('dm-mapping-stepper-subscription-static')).toBeNull();
      expect(q('dm-mapping-stepper-subscription-dynamic-panel')).toBeNull();
      const text = fixture.nativeElement.textContent;
      expect(text).toContain('c8y_SensorPhone');
      expect(text).toContain('iPhone 15');
    });

    it('starts on skip and writes nothing', () => {
      expect(component.contextChoice).toBe('skip');
      expect(hasPendingSubscriptionChanges(component.selection)).toBe(false);
    });

    it('choosing the type adds it to the existing types and is dynamic', () => {
      component.setContextChoice('type');
      expect(emitted.at(-1)!.mode).toBe('dynamic');
      expect(emitted.at(-1)!.types).toEqual(['c8y_Old', 'c8y_SensorPhone']);
      expect(emitted.at(-1)!.groups).toBeNull();
      expect(component.contextChoice).toBe('type');
    });

    it('choosing the group switches from the type and keeps the existing groups', () => {
      component.setContextChoice('type');
      component.setContextGroup('g9');
      expect(emitted.at(-1)!.mode).toBe('dynamic');
      expect(emitted.at(-1)!.types).toBeNull();
      expect(emitted.at(-1)!.groups!.map(g => g.id)).toEqual(['g1', 'g9']);
      expect(component.contextChoice).toBe('group');
    });

    it('choosing the device is static', () => {
      component.setContextChoice('device');
      expect(emitted.at(-1)!.mode).toBe('static');
      expect(emitted.at(-1)!.devices!.map(d => d.id)).toEqual(['d1', 'dev1']);
    });

    it('skip clears the pending choice', () => {
      component.setContextChoice('type');
      component.setContextChoice('skip');
      expect(emitted.at(-1)!.mode).toBe('skip');
      expect(hasPendingSubscriptionChanges(component.selection)).toBe(false);
    });

    it('has no suggestion block without suggestions', async () => {
      await create();
      expect(component.contextMode).toBe(false);
      expect(q('dm-mapping-stepper-subscription-skip')).not.toBeNull();
      expect(q('dm-mapping-stepper-subscription-suggestions')).toBeNull();
    });
  });
});
