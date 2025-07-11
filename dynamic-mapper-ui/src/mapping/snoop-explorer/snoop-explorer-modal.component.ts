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

import { ChangeDetectorRef, Component, Input, OnDestroy, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { JsonEditorComponent, Mapping, Substitution, MappingEnriched, SharedService, Feature } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { HttpStatusCode } from '@angular/common/http';

@Component({
  selector: 'd11r-snoop-explorer-modal',
  templateUrl: './snoop-explorer-modal.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class SnoopExplorerComponent implements OnInit, OnDestroy {
  constructor(
    private mappingService: MappingService,
    private alertService: AlertService, public sharedService: SharedService,
    private cdr: ChangeDetectorRef
  ) { }

  @Input() enrichedMapping: MappingEnriched;

  @ViewChild('editorGeneral', { static: false })
  editorGeneral: JsonEditorComponent;
  @ViewChild('modal', { static: false })
  private modal: any;

  pending: boolean = false;
  mapping: Mapping;
  closeSubject: Subject<Substitution> = new Subject();
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
  feature: Feature;
  private readonly destroy$ = new Subject<void>();

  async ngOnInit(): Promise<void> {
    try {
      this.validateInputs();
      this.initializeComponent();
      this.feature = await this.sharedService.getFeatures();
      this.cdr.detectChanges();
    } catch (error) {
      console.error('Error initializing snoop explorer:', error);
      this.alertService.danger('Failed to initialize component');
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.closeSubject.complete();
  }

  onCancel(): void {
    this.modal?._dismiss();
  }

  onSelectSnoopedTemplate(event: Event): void {
    const target = event.target as HTMLSelectElement;
    const index = parseInt(target.value, 10);
    this.initSnoopedTemplate(index);
  }


  initSnoopedTemplate(index: number) {
    if (!this.isValidTemplateIndex(index)) {
      console.error('Invalid template index:', index);
      return;
    }

    try {
      this.index = index;
      const templateString = this.mapping.snoopedTemplates[index];

      if (!templateString) {
        console.warn('Empty template at index:', index);
        this.template = {};
        return;
      }

      this.template = JSON.parse(templateString);
    } catch (error) {
      console.error('Failed to parse template JSON:', error);
      this.alertService.warning('Invalid template format');
      this.template = {};
    }
  }

  private validateInputs(): void {
    if (!this.enrichedMapping?.mapping) {
      throw new Error('Invalid enriched mapping provided');
    }

    this.mapping = this.enrichedMapping.mapping;

    if (!this.mapping.snoopedTemplates?.length) {
      throw new Error('No snooped templates available');
    }
  }

  private initializeComponent(): void {
    this.index = 0;
    this.labels = {
      ok: 'Delete templates',
      cancel: 'Close'
    };
    this.initSnoopedTemplate(0);
  }

  private isValidTemplateIndex(index: number): boolean {
    return typeof index === 'number' &&
      index >= 0 &&
      index < (this.mapping?.snoopedTemplates?.length || 0);
  }

  async onResetSnoop(): Promise<void> {
    if (this.pending) return;

    try {
      this.pending = true;
      const result = await this.mappingService.resetSnoop({
        id: this.mapping.id
      });

      if (result.status === HttpStatusCode.Created) {
        this.alertService.success(
          `Reset snooping for mapping ${this.mapping.id}`
        );
      } else {
        this.alertService.warning(
          `Failed to reset snooping for mapping ${this.mapping.id}`
        );
      }
    } catch (error) {
      console.error('Error resetting snoop:', error);
      this.alertService.danger(
        `Error resetting snooping for mapping ${this.mapping.id}`
      );
    } finally {
      this.pending = false;
      this.modal._dismiss();
    }
  }


  async onUpdateSourceTemplate(): Promise<void> {
    if (this.pending) return;

    try {
      this.pending = true;
      const result = await this.mappingService.updateTemplate({
        id: this.mapping.id,
        index: this.index
      });

      if (result.status === HttpStatusCode.Created) {
        this.alertService.success(
          `Updated source template for mapping ${this.mapping.id}`
        );
        this.mappingService.refreshMappings(this.mapping.direction);
      } else {
        this.alertService.warning(
          `Failed to update source template for mapping ${this.mapping.id}`
        );
      }
    } catch (error) {
      console.error('Error updating source template:', error);
      this.alertService.danger(
        `Error updating source template for mapping ${this.mapping.id}`
      );
    } finally {
      this.pending = false;
      this.modal._dismiss();
    }
  }
}
