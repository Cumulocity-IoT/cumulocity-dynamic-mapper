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
  addedTypes,
  changedDevices,
  changedGroups,
  changedTypes,
  OutboundSubscriptionSelection,
  SubscriptionMode,
  SubscriptionSuggestions
} from './outbound-subscription-selection.model';

const MAX_LISTED_DEVICES = 5;

/**
 * The Subscription step of an outbound mapping (stepper) and its tab (unified editor).
 *
 * It only edits an {@link OutboundSubscriptionSelection}; the host owns that object, because the
 * unified editor destroys this component on every tab switch. The selection is written when the
 * mapping is saved. The four selector drawers are the ones of the Subscription outbound page.
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

  loading = false;
  loadFailed = false;
  showTypeSelector = false;
  showGroupSelector = false;
  showDeviceTree = false;
  showDeviceTable = false;

  async ngOnInit(): Promise<void> {
    if (this.selection.baseline) return;
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

  get actionsDisabled(): boolean {
    return !this.canEdit || !this.ready;
  }

  setMode(mode: SubscriptionMode): void {
    if (this.selection.mode === mode) return;
    this.update({ mode });
  }

  // ----- current (pending where edited, otherwise as loaded) -----

  get types(): string[] { return this.selection.types ?? this.selection.baseline?.types ?? []; }
  get groups(): Device[] { return this.selection.groups ?? this.selection.baseline?.groups ?? []; }
  get devices(): Device[] { return this.selection.devices ?? this.selection.baseline?.devices ?? []; }

  get typesPending(): boolean { return changedTypes({ ...this.selection, mode: 'dynamic' }); }
  get groupsPending(): boolean { return changedGroups({ ...this.selection, mode: 'dynamic' }); }
  get devicesPending(): boolean { return changedDevices({ ...this.selection, mode: 'static' }); }

  get removedTypes(): string[] {
    return (this.selection.baseline?.types ?? []).filter(t => !this.types.includes(t));
  }
  get removedGroups(): Device[] {
    return (this.selection.baseline?.groups ?? []).filter(g => !this.groups.some(x => x.id === g.id));
  }
  get removedDevicesCount(): number {
    return (this.selection.baseline?.devices ?? []).filter(d => !this.devices.some(x => x.id === d.id)).length;
  }

  get listedDevices(): Device[] { return this.devices.slice(0, MAX_LISTED_DEVICES); }
  get moreDevices(): number { return Math.max(0, this.devices.length - MAX_LISTED_DEVICES); }

  get canResync(): boolean { return addedTypes({ ...this.selection, types: this.types }).length > 0; }

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
      const type = this.contextType;
      if (type && this.types.includes(type) && !this.isTypeSubscribed(type)) return 'type';
      if (this.contextGroups.some(g => this.groups.some(x => x.id === g.id) && !this.isGroupSubscribed(g.id))) return 'group';
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
        this.update({ mode: 'skip', types: null, groups: null, devices: null, resyncNewTypes: false });
        break;
      case 'type': {
        const type = this.contextType;
        if (!type) return;
        const base = this.selection.baseline?.types ?? [];
        this.update({ mode: 'dynamic', types: Array.from(new Set([...base, type])), groups: null, devices: null });
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

  setContextGroup(id: string): void {
    this.selectedGroupId = id;
    this.setContextChoice('group');
  }

  // ----- drawers -----

  onCommitTypes(types: string[]): void {
    this.showTypeSelector = false;
    this.update({ types });
  }
  onCommitGroups(groups: IIdentified[]): void {
    this.showGroupSelector = false;
    this.update({ groups: groups as Device[] });
  }
  onCommitDevices(devices: IIdentified[]): void {
    this.showDeviceTree = false;
    this.showDeviceTable = false;
    this.update({ devices: devices as Device[] });
  }

  setResync(resyncNewTypes: boolean): void {
    this.update({ resyncNewTypes });
  }

  private update(patch: Partial<OutboundSubscriptionSelection>): void {
    this.selection = { ...this.selection, ...patch };
    this.selectionChange.emit(this.selection);
  }
}
