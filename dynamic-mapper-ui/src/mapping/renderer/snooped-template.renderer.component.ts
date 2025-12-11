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
import { NgIf } from '@angular/common';
import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { SnoopExplorerComponent } from '../snoop-explorer/snoop-explorer-modal.component';

@Component({
  selector: 'd11r-mapping-renderer-snooped',
  template: `
    <div *ngIf="hasSnoopedTemplates">
      <button
        class="btn btn-link"
        [title]="context.item.id"
        (click)="exploreSnoopedTemplates()"
        style="padding-top: 0px; padding-bottom: 10px;"
      >
        <span>{{ snoopedTemplatesCount }}</span>
      </button>
    </div>
  `,
  standalone: true,
  imports: [NgIf]
})
export class SnoopedTemplateRendererComponent {
  constructor(
    public readonly context: CellRendererContext,
    private readonly bsModalService: BsModalService
  ) {}

  get hasSnoopedTemplates(): boolean {
    return (this.context.value.snoopedTemplates?.length ?? 0) > 0;
  }

  get snoopedTemplatesCount(): number {
    return this.context.value.snoopedTemplates?.length ?? 0;
  }

  exploreSnoopedTemplates(): void {
    this.bsModalService.show(SnoopExplorerComponent, {
      initialState: {
        enrichedMapping: this.context.item,
        labels: { ok: 'Cancel', cancel: 'Cancel' }
      },
      class: '_modal-lg'
    });
  }
}
