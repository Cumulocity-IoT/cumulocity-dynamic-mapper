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
import {
  Component,
  OnDestroy,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  AlertService,
  DropAreaComponent,
  ModalLabels
} from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { uuidCustom, Mapping } from '../../shared';

@Component({
  selector: 'd11r-mapping-import-extension',
  templateUrl: './import-modal.component.html',
  styleUrls: ['./import-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class ImportMappingsComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;
  private importCanceled: boolean = false;
  progress$: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  isLoading: boolean;
  isAppCreated: boolean;
  errorMessage: string;
  successText: string = 'Imported mappings';
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private mappingService: MappingService,
    private alertService: AlertService
  ) {}

  onFileDroppedEvent(event) {
    if (event && event.length > 0) {
      // eslint-disable-next-line prefer-destructuring
      const file = event[0].file;
      this.onFile(file);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.progress$.next(0);
    // const ms = await file.text();
    const ms = await file.text();
    const mappings: Mapping[] = JSON.parse(ms);
    const countMappings = mappings.length;
    const errors = [];
    mappings.forEach(async (m, i) => {
      try {
        m.identifier = uuidCustom();
        m.lastUpdate = Date.now();
        m.active = false;
        await this.mappingService.createMapping(m);
        this.progress$.next((100 * i) / countMappings);
      } catch (ex) {
        this.errorMessage = `Failed to import mappings: ${i}`;
        errors.push(this.errorMessage);
        this.alertService.warning(ex);
      }
    });
    this.isAppCreated = true;
    this.progress$.next(100);
    this.isLoading = false;
  }

  onDismiss() {
    this.cancelFileUpload();
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDone() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  private cancelFileUpload() {
    this.importCanceled = true;
  }

  ngOnDestroy() {
    this.progress$.unsubscribe();
  }
}
