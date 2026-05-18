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

import { Component, OnDestroy, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { DEPRECATION_NOTICE_VERSION, SharedService } from '../../shared';

@Component({
  selector: 'd11r-deprecation-notice-modal',
  templateUrl: './deprecation-notice-modal.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule]
})
export class DeprecationNoticeModalComponent implements OnDestroy {
  readonly closeSubject = new Subject<boolean>();
  readonly currentVersion = DEPRECATION_NOTICE_VERSION;
  isPending = false;
  isClosing = false;

  @ViewChild('modal', { static: false }) private modal: any;

  constructor(
    private sharedService: SharedService,
    private alertService: AlertService
  ) {}

  ngOnDestroy(): void {
    this.closeSubject.complete();
  }

  onDismiss(): void {
    if (this.isClosing) return;
    this.isClosing = true;
    this.closeSubject.next(false);
    this.closeSubject.complete();
    this.modal?._dismiss();
  }

  async onAccept(): Promise<void> {
    if (this.isPending || this.isClosing) return;
    this.isPending = true;
    this.isClosing = true;
    try {
      await this.sharedService.updateServiceConfiguration({
        acceptedDeprecationNotice: DEPRECATION_NOTICE_VERSION
      });
      this.closeSubject.next(true);
      this.closeSubject.complete();
      this.modal?._dismiss();
    } catch (error) {
      console.error('Failed to save deprecation notice acceptance:', error);
      this.alertService.warning(
        'Could not save acceptance. The notice may appear again next time.'
      );
      this.isPending = false;
      this.isClosing = false;
    }
  }
}
