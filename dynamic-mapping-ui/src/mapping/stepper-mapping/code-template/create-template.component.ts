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
  OnDestroy,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';

@Component({
  selector: 'd11r-create-template',
  templateUrl: './create-template.component.html',
  encapsulation: ViewEncapsulation.None
})
export class CreateTemplateComponent implements OnInit, OnDestroy {
  closeSubject: Subject<string> = new Subject();
  labels: ModalLabels = { ok: 'Create', cancel: 'Cancel' };

  name: string = '';
  valid: boolean = false;

  constructor() {}

  ngOnInit(): void {
    this.closeSubject = new Subject();
  }

  onDismiss() {
    this.closeSubject.next('CANCEL');
    this.closeSubject.complete();
  }

  onClose() {
    this.closeSubject.next(this.name);
    this.closeSubject.complete();
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
