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
import { Component } from '@angular/core';
import { CellRendererContext, CommonModule } from '@c8y/ngx-components';
import { MAPPING_STATUS_UNSPECIFIED, MAPPING_STATUS_UNSPECIFIED_LABEL } from '../../shared';

@Component({
  selector: 'd11r-mapping-renderer-name',
  template: `
    <span
      [attr.data-cy]="'dm-mapping-name-' + context.item.id"
      [class]="isUnmapped ? 'text-bold' : 'text-normal'"
      [title]="isUnmapped ? unmappedHint : context.item.id"
      >{{ displayName }}</span
    >
  `,
  standalone: true,
  imports: []
})
export class NameRendererComponent {
  readonly unmappedHint =
    'Messages that matched no mapping, plus errors raised before a mapping could be resolved. ' +
    'A rising count here usually means a topic mismatch, or a mapping that is inactive or not ' +
    'deployed to this connector.';

  constructor(public context: CellRendererContext) {}

  /**
   * Keyed off the identifier, not the displayed name: the label is a display string that can be
   * reworded (it was "Unspecified"), and matching on it silently loses the highlight when it is.
   */
  get isUnmapped(): boolean {
    return this.context.item?.identifier === MAPPING_STATUS_UNSPECIFIED;
  }

  /**
   * The catch-all row is labelled here rather than showing the `name` the backend sent: that
   * name can be stale (an older microservice, or a status restored from the persisted fragment)
   * and would still read "Unspecified".
   */
  get displayName(): string {
    return this.isUnmapped ? MAPPING_STATUS_UNSPECIFIED_LABEL : this.context.value;
  }
}
