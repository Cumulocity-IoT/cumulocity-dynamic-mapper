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

import { Device } from '../../shared/mapping/notification-subscription.model';

/**
 * What the Subscription step of an outbound mapping collects. Nothing is sent to the backend
 * while the user edits it; {@link SubscriptionSelectionService.apply} writes it after the
 * mapping itself was saved.
 *
 * - `skip`: write nothing.
 * - `dynamic`: `types` and `groups` are written (subscriptions follow devices created later, or
 *   joining a group).
 * - `static`: `devices` is written to the static device subscription.
 *
 * A list that is `null` was not touched and is left as it is on the server.
 */
export type SubscriptionMode = 'skip' | 'dynamic' | 'static';

/** The subscriptions as they were on the server when the step loaded them. */
export interface SubscriptionBaseline {
  /** False when the backend reports outbound mappings as disabled: there is nothing to subscribe. */
  available: boolean;
  types: string[];
  groups: Device[];
  devices: Device[];
}

export interface OutboundSubscriptionSelection {
  mode: SubscriptionMode;
  types: string[] | null;
  groups: Device[] | null;
  devices: Device[] | null;
  /** Dynamic mode: also subscribe the existing devices of the types added in this selection. */
  resyncNewTypes: boolean;
  /** Set by the step once it has loaded the current subscriptions. */
  baseline?: SubscriptionBaseline;
}

/** One-click proposals the Message Explorer passes on from the message's source device. */
export interface SubscriptionSuggestions {
  deviceId?: string;
  deviceName?: string;
  deviceType?: string;
  groups?: { id: string; name: string }[];
}

export function emptySelection(): OutboundSubscriptionSelection {
  return { mode: 'skip', types: null, groups: null, devices: null, resyncNewTypes: false };
}

const sameIds = (a: { id: string }[], b: { id: string }[]): boolean => {
  if (a.length !== b.length) return false;
  const ids = new Set(a.map(d => d.id));
  return b.every(d => ids.has(d.id));
};

const sameStrings = (a: string[], b: string[]): boolean =>
  a.length === b.length && new Set(a).size === new Set([...a, ...b]).size;

/** Types in the selection that the server does not have yet. */
export function addedTypes(selection: OutboundSubscriptionSelection): string[] {
  const before = new Set(selection.baseline?.types ?? []);
  return (selection.types ?? []).filter(t => !before.has(t));
}

export function changedTypes(s: OutboundSubscriptionSelection): boolean {
  return s.mode === 'dynamic' && s.types !== null && !sameStrings(s.types, s.baseline?.types ?? []);
}

export function changedGroups(s: OutboundSubscriptionSelection): boolean {
  return s.mode === 'dynamic' && s.groups !== null && !sameIds(s.groups, s.baseline?.groups ?? []);
}

export function changedDevices(s: OutboundSubscriptionSelection): boolean {
  return s.mode === 'static' && s.devices !== null && !sameIds(s.devices, s.baseline?.devices ?? []);
}

/** True when saving would write something. An untouched selection writes nothing and shows no alert. */
export function hasPendingSubscriptionChanges(s: OutboundSubscriptionSelection | undefined): boolean {
  return !!s && (changedTypes(s) || changedGroups(s) || changedDevices(s));
}
