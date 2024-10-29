/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { CellRendererContext } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { SnoopExplorerComponent } from '../snoop-explorer/snoop-explorer-modal.component';

@Component({
  selector: 'd11r-mapping-renderer-snooped',
  template: `
    <div *ngIf="context.value.snoopedTemplates?.length > 0">
      <button
        class="btn btn-link"
        title="{{ context.item.id }}"
        (click)="exploreSnoopedTemplates()"
        style="padding-top: 0px; padding-bottom: 10px;"
      >
        <span>{{
          context.value.snoopedTemplates
            ? context.value.snoopedTemplates?.length
            : ''
        }}</span>
      </button>
    </div>
  `
})
export class SnoopedTemplateRendererComponent {
  constructor(
    public context: CellRendererContext,
    public bsModalService: BsModalService
  ) {}
  exploreSnoopedTemplates() {
    const initialState = {
      enrichedMapping: this.context.item,
      labels: {
        ok: 'Cancel',
        cancel: 'Cancel'
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      SnoopExplorerComponent,
      { initialState }
    );
  }
}
