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
import {
  Component,
  Input,
  OnDestroy,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Mapping, MappingEnriched } from '../../../shared';
import { AdvisorAction } from '../../shared/stepper.model';

@Component({
  selector: 'd11r-advice-action',
  templateUrl: './advice-action.component.html',
  encapsulation: ViewEncapsulation.None
})
export class AdviceActionComponent implements OnInit, OnDestroy {
  @Input() enrichedMapping: MappingEnriched;
  mapping: Mapping;
  closeSubject: Subject<string> = new Subject();
  labels: ModalLabels = { ok: 'Select', cancel: 'Cancel' };

  selection: string = '';
  valid: boolean = false;
  AdvisorAction = AdvisorAction;

  constructor() {}

  ngOnInit(): void {
    this.closeSubject = new Subject();
  }

  onDismiss() {
    this.closeSubject.next('CANCEL');
    this.closeSubject.complete();
  }

  onClose() {
    this.closeSubject.next(this.selection);
    this.closeSubject.complete();
  }

  onSelection(t) {
    this.selection = t;
    this.valid = true;
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
