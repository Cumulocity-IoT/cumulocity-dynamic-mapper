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

import { ConfirmsUnsavedChanges, unsavedChangesGuard } from './unsaved-changes.guard';

/**
 * This guard is the UI's only protection against losing unsaved edits — there is no other
 * CanDeactivate anywhere — so the failure modes that matter are the ones where it silently
 * stops applying, or wedges the router.
 */
describe('unsavedChangesGuard', () => {
  const run = (component: unknown) =>
    (unsavedChangesGuard as any)(component, null, null, null);

  it('delegates to the component', async () => {
    const component: ConfirmsUnsavedChanges = {
      confirmLeaveWithUnsavedChanges: jasmine.createSpy().and.resolveTo(true)
    };

    await expectAsync(run(component)).toBeResolvedTo(true);
    expect(component.confirmLeaveWithUnsavedChanges).toHaveBeenCalled();
  });

  it('blocks the navigation when the component says no', async () => {
    const component: ConfirmsUnsavedChanges = {
      confirmLeaveWithUnsavedChanges: jasmine.createSpy().and.resolveTo(false)
    };

    await expectAsync(run(component)).toBeResolvedTo(false);
  });

  it('lets navigation through for a component torn down mid-navigation', () => {
    // Returning a falsy/blocking value here would wedge the router with no way out.
    expect(run(null)).toBe(true);
    expect(run(undefined)).toBe(true);
  });

  it('lets navigation through for a component that does not implement the hook', () => {
    expect(run({} as ConfirmsUnsavedChanges)).toBe(true);
  });
});
