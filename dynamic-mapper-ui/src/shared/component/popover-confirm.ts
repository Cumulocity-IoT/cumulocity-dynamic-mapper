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

import { PopoverConfirmButtons, PopoverConfirmComponent } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';

export interface PopoverConfirmOptions {
  /** Popover title; usually the action, e.g. "Delete substitution". */
  title: string;
  /** Short consequence text. Keep it brief: a popover is not a dialog. */
  message: string;
  /** Label of the confirming button. Defaults to "Delete". */
  okLabel?: string;
  /** Button style of the confirming button. Defaults to "danger". */
  okStatus?: 'danger' | 'warning' | 'primary';
  cancelLabel?: string;
}

/**
 * Opens a `<c8y-popover-confirm>` placed next to its trigger button and resolves with the answer:
 * `true` when the user confirms; `false` on Cancel, Escape, or a click outside the popover.
 *
 * Use it for a confirmation that starts from a button in a template. A confirmation that starts in
 * service code, from a grid row callback, or from a dropdown item (the menu is destroyed when it
 * closes, taking the popover with it) still needs the modal in `ConfirmationModalService`.
 */
export async function confirmWithPopover(
  popover: PopoverConfirmComponent,
  options: PopoverConfirmOptions
): Promise<boolean> {
  popover.title = options.title;
  popover.message = options.message;
  const buttons: PopoverConfirmButtons[] = [
    { label: options.cancelLabel ?? gettext('Cancel'), action: () => Promise.resolve(false) },
    { label: options.okLabel ?? gettext('Delete'), status: options.okStatus ?? 'danger', action: () => Promise.resolve(true) }
  ];
  try {
    return !!(await popover.show(buttons));
  } catch {
    // Escape or a click outside rejects.
    return false;
  }
}
