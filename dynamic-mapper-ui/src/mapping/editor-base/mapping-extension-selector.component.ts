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

import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, ChangeDetectorRef, Component, effect, inject, input } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { merge, Observable } from 'rxjs';
import { Mapping } from '../../shared';

/**
 * Extension name / event / parameter selectors for the Select Templates step (stepper) and tab
 * (unified editor). One instance is rendered per side (source / target); the host decides via
 * {@link showSelectors} which side carries them. Also renders the PROTOBUF_INTERNAL note, which
 * replaces the template editor when the payload structure comes from the Protobuf schema.
 *
 * `dataCyPrefix` keeps the data-cy attributes distinct per host (e2e selectors).
 */
@Component({
  selector: 'd11r-mapping-extension-selector',
  templateUrl: 'mapping-extension-selector.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CoreModule, CommonModule]
})
export class MappingExtensionSelectorComponent {
  readonly form = input.required<FormGroup>();
  readonly side = input<'source' | 'target'>('source');
  readonly mapping = input.required<Mapping>();
  readonly showSelectors = input(false);
  readonly showInternalNote = input(false);
  readonly isLoading = input(false);
  readonly hasParameter = input(false);
  readonly extensionItems = input<string[]>([]);
  readonly extensionEvents = input.required<Observable<{ label: string; value: string; description?: string }[]>>();
  readonly dataCyPrefix = input('dm-mapping-stepper');

  private readonly cdr = inject(ChangeDetectorRef);

  constructor() {
    // The host fills the form programmatically (when it opens a saved mapping and when the extension
    // changes), which fires no event in this component's view. The template shows the form's validity and
    // the selected event's description, so mark the view dirty whenever the form's value or status changes.
    effect((onCleanup) => {
      const form = this.form();
      if (!form) return;
      const subscription = merge(form.valueChanges, form.statusChanges).subscribe(() => this.cdr.markForCheck());
      onCleanup(() => subscription.unsubscribe());
    });
  }

  /** Description of the currently selected event, shown below the dropdown instead of inside it. */
  descriptionFor(events: { value: string; description?: string }[] | null): string | undefined {
    return events?.find(e => e.value === this.form()?.get('eventName')?.value)?.description;
  }
}
