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

import { ChangeDetectorRef, Component, OnDestroy, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { DEPRECATION_NOTICE_VERSION, Direction, Mapping, SharedService, TransformationType } from '../../shared';
import { CommonModule } from '@angular/common';
import { MappingService } from '../core/mapping.service';

@Component({
  selector: 'd11r-deprecation-notice-modal',
  templateUrl: './deprecation-notice-modal.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule]
})
export class DeprecationNoticeModalComponent implements OnInit, OnDestroy {
  readonly closeSubject = new Subject<boolean>();
  isPending = false;
  isLoading = true;
  isClosing = false;
  affectedMappings: Mapping[] = [];

  @ViewChild('modal', { static: false }) private modal: any;

  constructor(
    private sharedService: SharedService,
    private mappingService: MappingService,
    private alertService: AlertService,
    private cdr: ChangeDetectorRef
  ) {}

  async ngOnInit(): Promise<void> {
    try {
      const [inbound, outbound] = await Promise.all([
        this.mappingService.getMappings(Direction.INBOUND),
        this.mappingService.getMappings(Direction.OUTBOUND)
      ]);
      this.affectedMappings = [...inbound, ...outbound].filter(
        m => m.transformationType === TransformationType.SUBSTITUTION_AS_CODE
      );
    } catch (error) {
      console.error('Failed to load affected mappings:', error);
    } finally {
      this.isLoading = false;
      this.cdr.detectChanges();
    }
  }

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
