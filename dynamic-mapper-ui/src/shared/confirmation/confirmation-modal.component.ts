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
  AfterViewInit,
  Component,
  Input,
  OnDestroy,
  OnInit,
  ViewChild
} from '@angular/core';
import {
  ConfirmModalComponent,
  CoreModule,
  ModalLabels,
  Status,
  StatusType
} from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';

@Component({
  selector: 'd11r-confirmation-modal',
  templateUrl: 'confirmation-modal.component.html',
  standalone: true,
  imports:[CoreModule]
})
export class ConfirmationModalComponent
  implements OnInit, AfterViewInit, OnDestroy
{
  @Input() title: string;
  @Input() message: string;
  @ViewChild('modalRef', { static: false }) modalRef: ConfirmModalComponent;
  messageTranslated: string;
  closeSubject: Subject<boolean> = new Subject();
  @Input() labels: ModalLabels = {
    ok: gettext('Disconnect'),
    cancel: gettext('Cancel')
  };
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.messageTranslated = this.translateService.instant(
      gettext(this.message)
    );
  }

  async ngAfterViewInit() {
    try {
      await this.modalRef.result;
      this.onClose();
    } catch (error) {
      this.onDismiss();
    }
  }

  onClose() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDismiss() {
    this.closeSubject.next(false);
    this.closeSubject.complete();
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
