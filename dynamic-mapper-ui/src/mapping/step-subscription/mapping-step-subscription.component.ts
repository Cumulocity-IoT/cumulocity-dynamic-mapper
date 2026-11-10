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

import {
  Component,
  EventEmitter,
  inject,
  Input,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { IIdentified } from '@c8y/client';
import { gettext } from '@c8y/ngx-components/gettext';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Feature } from '../../shared';
import { Device } from '../../shared/mapping/notification-subscription.model';
import { SubscriptionSelectionService } from '../core/subscription-selection.service';
import { DeviceSelectorTreeComponent } from '../subscription/subscription-static-tree/device-selector-tree.component';
import { DeviceSelectorTableComponent } from '../subscription/subscription-static-table/device-selector-table.component';
import { GroupSelectorComponent } from '../subscription/subscription-dynamic-group/group-selector.component';
import { TypeSelectorComponent } from '../subscription/subscription-dynamic-type/type-selector.component';
import {
  changedDevices,
  changedGroups,
  changedTypes,
  hasEditedLists,
  OutboundSubscriptionSelection,
  SubscriptionMode,
  SubscriptionSuggestions
} from './outbound-subscription-selection.model';

/**
 * The Subscription step of an outbound mapping (stepper) and its tab (unified editor).
 *
 * It only edits an {@link OutboundSubscriptionSelection}; the host owns that object, because the
 * unified editor destroys this component on every tab switch. The selection is written when the
 * mapping is saved. The four selectors are the ones of the Subscription outbound page. Three are embedded inline; the device table opens as a drawer.
 */
@Component({
  selector: 'd11r-mapping-step-subscription',
  templateUrl: 'mapping-step-subscription.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [
    CoreModule,
    CommonModule,
    FormsModule,
    PopoverModule,
    DeviceSelectorTreeComponent,
    DeviceSelectorTableComponent,
    GroupSelectorComponent,
    TypeSelectorComponent
  ]
})
export class MappingStepSubscriptionComponent implements OnInit {
  @Input({ required: true }) selection!: OutboundSubscriptionSelection;
  @Input() suggestions?: SubscriptionSuggestions;
  @Input() feature?: Feature;
  /** The host data-cy prefix, so stepper and unified editor keep separate test ids. */
  @Input() dataCyPrefix = 'dm-mapping-stepper';
  @Output() selectionChange = new EventEmitter<OutboundSubscriptionSelection>();

  private readonly selectionService = inject(SubscriptionSelectionService);
  private readonly alertService = inject(AlertService);

  /**
   * Which option of the chosen mode is selected, and therefore expanded. The options are radio
   * buttons: choosing Dynamic selects "by device type", choosing Static selects "device selection".
   */
  expanded = { types: false, groups: false, devices: false, table: false };

  private expandFor(mode: SubscriptionMode): void {
    this.expanded = {
      types: mode === 'dynamic',
      groups: false,
      devices: mode === 'static',
      table: false
    };
  }

  /**
   * Selects one option of the chosen mode; the others collapse. The device table has no room in the
   * page, so selecting it opens its drawer; the option then offers a link to open it again.
   */
  selectSub(key: keyof typeof this.expanded): void {
    this.expanded = {
      types: key === 'types',
      groups: key === 'groups',
      devices: key === 'devices',
      table: key === 'table'
    };
    if (key === 'table') this.showDeviceTable = true;
  }

  /** The device table is the one selector that opens as a drawer (when its option is selected): it needs too much room for the page. */
  showDeviceTable = false;

  loading = false;
  loadFailed = false;

  async ngOnInit(): Promise<void> {
    // The host recreates this component on a tab switch; an already chosen mode opens as chosen.
    this.expandFor(this.selection.mode);
    // Reload on every open so a long session does not write on top of old data; but keep the
    // baseline the user's edits were diffed against while there are edits.
    if (this.selection.baseline && hasEditedLists(this.selection)) return;
    this.loading = true;
    try {
      const baseline = await this.selectionService.loadBaseline();
      this.update({ baseline });
    } catch (error) {
      this.loadFailed = true;
      this.alertService.danger(gettext('Failed to load subscriptions:'), (error as Error)?.message);
    } finally {
      this.loading = false;
    }
  }

  get canEdit(): boolean {
    return !!(this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole);
  }

  /** Editing is only safe on top of a loaded baseline: the drawers re-commit the complete list. */
  get ready(): boolean {
    return !!this.selection.baseline?.available && !this.loading && !this.loadFailed;
  }

  get unavailable(): boolean {
    return !!this.selection.baseline && !this.selection.baseline.available;
  }

  setMode(mode: SubscriptionMode): void {
    if (this.selection.mode === mode) return;
    this.expandFor(mode);
    this.update({ mode });
  }

  // ----- current (pending where edited, otherwise as loaded) -----

  get types(): string[] { return this.selection.types ?? this.selection.baseline?.types ?? []; }
  get groups(): Device[] { return this.selection.groups ?? this.selection.baseline?.groups ?? []; }
  get devices(): Device[] { return this.selection.devices ?? this.selection.baseline?.devices ?? []; }

  get typesPending(): boolean { return changedTypes({ ...this.selection, mode: 'dynamic' }); }
  get groupsPending(): boolean { return changedGroups({ ...this.selection, mode: 'dynamic' }); }
  get devicesPending(): boolean { return changedDevices({ ...this.selection, mode: 'static' }); }


  // ----- device context from the Message Explorer -----

  /**
   * True when the mapping was started from a message: the step then offers only that message's
   * device, its type and its groups. Without a device context (Add mapping, existing mapping) it
   * offers Skip, Dynamic and Static instead.
   */
  get contextMode(): boolean {
    const s = this.suggestions;
    return !!(s?.deviceId || s?.deviceType || s?.groups?.length);
  }

  get contextType(): string | undefined { return this.suggestions?.deviceType || undefined; }
  get contextGroups(): { id: string; name: string }[] { return this.suggestions?.groups ?? []; }
  get contextDevice(): { id: string; name?: string } | undefined {
    const id = this.suggestions?.deviceId;
    return id ? { id, name: this.suggestions?.deviceName } : undefined;
  }

  /** The group picked when the device has several groups; defaults to the first. */
  selectedGroupId: string | null = null;

  get contextGroupId(): string | null {
    return this.selectedGroupId ?? this.contextGroups[0]?.id ?? null;
  }

  /** Which radio of the device-context list is selected, derived from the selection the host holds. */
  get contextChoice(): 'skip' | 'type' | 'group' | 'device' {
    const { mode } = this.selection;
    if (mode === 'static') return 'device';
    if (mode === 'dynamic') {
      // The raw lists: a choice that is already subscribed still counts as chosen.
      const type = this.contextType;
      if (type && this.selection.types?.includes(type)) return 'type';
      if (this.contextGroups.some(g => this.selection.groups?.some(x => x.id === g.id))) return 'group';
    }
    return 'skip';
  }

  isTypeSubscribed(type: string): boolean { return !!this.selection.baseline?.types.includes(type); }
  isGroupSubscribed(id: string): boolean { return !!this.selection.baseline?.groups.some(g => g.id === id); }
  isDeviceSubscribed(id: string): boolean { return !!this.selection.baseline?.devices.some(d => d.id === id); }

  /**
   * One choice at a time, as in the former drawer. Type and group are dynamic, the device is
   * static; the list of the kind not chosen is dropped, so nothing else is written.
   */
  setContextChoice(choice: 'skip' | 'type' | 'group' | 'device'): void {
    switch (choice) {
      case 'skip':
        this.update({ mode: 'skip', types: null, groups: null, devices: null, resyncTypes: [] });
        break;
      case 'type': {
        const type = this.contextType;
        if (!type) return;
        const base = this.selection.baseline?.types ?? [];
        this.update({ mode: 'dynamic', types: Array.from(new Set([...base, type])), groups: null, devices: null, resyncTypes: [] });
        break;
      }
      case 'group': {
        const group = this.contextGroups.find(g => g.id === this.contextGroupId);
        if (!group) return;
        const base = this.selection.baseline?.groups ?? [];
        const groups = base.some(g => g.id === group.id) ? base : [...base, { id: group.id, name: group.name } as Device];
        this.update({ mode: 'dynamic', groups, types: null, devices: null });
        break;
      }
      case 'device': {
        const device = this.contextDevice;
        if (!device) return;
        const base = this.selection.baseline?.devices ?? [];
        const devices = base.some(d => d.id === device.id) ? base : [...base, { id: device.id, name: device.name } as Device];
        this.update({ mode: 'static', devices, types: null, groups: null });
        break;
      }
    }
  }

  onContextSelect(choice: 'skip' | 'type' | 'group' | 'device', checked: boolean): void {
    if (checked && this.contextChoice !== choice) this.setContextChoice(choice);
  }

  /** Context mode: also subscribe the devices of the chosen type that exist already. */
  get contextResync(): boolean {
    const type = this.contextType;
    return !!type && this.selection.resyncTypes.includes(type);
  }

  setContextResync(checked: boolean): void {
    const type = this.contextType;
    if (type) this.setResyncTypes(checked ? [type] : []);
  }

  /** Items removed by the pending edit, so a removal is visible before saving. */
  get removedTypes(): number { return this.removedCount(this.selection.types, this.selection.baseline?.types); }
  get removedGroups(): number { return this.removedCount(this.selection.groups?.map(g => g.id), this.selection.baseline?.groups.map(g => g.id)); }
  get removedDevices(): number { return this.removedCount(this.selection.devices?.map(d => d.id), this.selection.baseline?.devices.map(d => d.id)); }

  private removedCount(now: string[] | null | undefined, before: string[] | undefined): number {
    if (!now || !before) return 0;
    const kept = new Set(now);
    return before.filter(x => !kept.has(x)).length;
  }

  setContextGroup(id: string): void {
    this.selectedGroupId = id;
    this.setContextChoice('group');
  }

  // ----- inline selectors: every edit updates the selection; it is written when the mapping is saved -----

  onTypesChange(types: string[]): void { this.update({ types }); }
  onGroupsChange(groups: IIdentified[]): void { this.update({ groups: groups as Device[] }); }
  /** The option that made the pending device change; both options edit the same list, but the label belongs where the user acted. */
  devicesEditedVia: 'tree' | 'table' = 'tree';

  onDevicesChange(devices: IIdentified[]): void {
    this.devicesEditedVia = 'tree';
    this.update({ devices: devices as Device[] });
  }

  onCommitDevices(devices: IIdentified[]): void {
    this.showDeviceTable = false;
    this.update({ devices: devices as Device[] });
    this.devicesEditedVia = 'table';
  }

  setResyncTypes(resyncTypes: string[]): void {
    this.update({ resyncTypes });
  }

  private update(patch: Partial<OutboundSubscriptionSelection>): void {
    this.selection = { ...this.selection, ...patch };
    this.selectionChange.emit(this.selection);
  }
}
