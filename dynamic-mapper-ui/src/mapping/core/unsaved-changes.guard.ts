/*
 * Copyright (c) 2025 Cumulocity GmbH
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

import { CanDeactivateFn } from '@angular/router';

/** Implemented by editors that can hold unsaved work. */
export interface ConfirmsUnsavedChanges {
  /** Resolves true when it is safe to leave — either nothing is unsaved, or the user agreed. */
  confirmLeaveWithUnsavedChanges(): Promise<boolean>;
}

/**
 * Blocks navigation away from an editor with unsaved edits until the user confirms.
 *
 * Deliberately a route guard rather than a check inside the Close handler: the editor is a routed
 * page, so the browser's Back button, a nav-bar click and the Close button are all the same
 * departure and must all be covered. Putting it in Close only would leave the two most likely
 * ways to lose work unguarded — and this is the UI's only unsaved-changes protection, so there is
 * no other net underneath it.
 */
export const unsavedChangesGuard: CanDeactivateFn<ConfirmsUnsavedChanges> = (component) => {
  // A component torn down mid-navigation, or one that opted out, must never wedge the router.
  if (!component?.confirmLeaveWithUnsavedChanges) return true;
  return component.confirmLeaveWithUnsavedChanges();
};
