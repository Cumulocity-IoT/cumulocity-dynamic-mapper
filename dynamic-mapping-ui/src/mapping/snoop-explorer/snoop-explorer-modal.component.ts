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

import { Component, Input, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { JsonEditorComponent, Mapping, MappingSubstitution, MappingEnriched } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { IFetchResponse } from '@c8y/client';
import { HttpStatusCode } from '@angular/common/http';

@Component({
  selector: 'd11r-snoop-explorer-modal',
  templateUrl: './snoop-explorer-modal.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class SnoopExplorerComponent implements OnInit {
  constructor(
    private mappingService: MappingService,
    private alertService: AlertService
  ) { }
  @Input() enrichedMapping: MappingEnriched;

  @ViewChild('editorGeneral', { static: false })
  editorGeneral: JsonEditorComponent;

  @ViewChild('modal', { static: false }) private modal;

  pending: boolean = false;
  mapping: Mapping;
  closeSubject: Subject<MappingSubstitution> = new Subject();
  labels: ModalLabels;
  template: any;
  index: number;

  editorOptionsGeneral = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    readOnly: true,
    statusBar: true
  };

  ngOnInit(): void {
    this.mapping = this.enrichedMapping.mapping;
    this.onSelectSnoopedTemplate(0);
    this.index = 0;
    this.labels = {
      ok: 'Delete templates',
      cancel: 'Close'
    };
  }

  onCancel() {
    this.modal._dismiss();
  }

  async onSelectSnoopedTemplate(index: any) {
    this.index = index;
    this.template = JSON.parse(this.mapping.snoopedTemplates[index]);
  }

  async onResetSnoop() {
    // console.log('Clicked onResetSnoop!');
    this.pending = true;
    const result: IFetchResponse = await this.mappingService.resetSnoop({
      id: this.mapping.id
    });
    this.pending = false;
    if (result.status == HttpStatusCode.Created) {
      this.alertService.success(
        `Reset snooping for mapping ${this.mapping.id}`
      );
    } else {
      this.alertService.warning(
        `Failed to reset snooping for mapping ${this.mapping.id}`
      );
      this.pending = false;
    }
    this.modal._dismiss();
  }


  async onUpdateSourceTemplate() {
    this.pending = true;
    const result: IFetchResponse = await this.mappingService.updateTemplate({
      id: this.mapping.id,
      index: this.index
    });
    this.pending = false;
    if (result.status == HttpStatusCode.Created) {
      this.alertService.success(
        `Update source template for mapping ${this.mapping.id}`
      );
      this.mappingService.refreshMappings(this.mapping.direction);
    } else {
      this.alertService.warning(
        `Failed to update source template for mapping ${this.mapping.id}`
      );
      this.pending = false;
    }
    this.modal._dismiss();
  }
}
