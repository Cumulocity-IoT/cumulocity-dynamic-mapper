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
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { IManagedObject } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { ConfirmationModalComponent, ExtensionStatus, Feature, SharedService } from '../../shared';
import { ExtensionService } from '../extension.service';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';

@Component({
  selector: 'd11r-mapping-extension-card',
  templateUrl: './extension-card.component.html',
  standalone: false
})
export class ExtensionCardComponent implements OnInit {
  @Input() app: IManagedObject;
  @Output() appDeleted: EventEmitter<void> = new EventEmitter();
  ExtensionStatus = ExtensionStatus;
  external: boolean = true;
  feature: Feature;

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private router: Router,
    private activatedRoute: ActivatedRoute,
    public bsModalService: BsModalService,
    private sharedService: SharedService
  ) { }

  async ngOnInit() {
    this.external = this.app?.['external'];
    this.feature = await this.sharedService.getFeatures();
  }

  async detail() {
    // this.router.navigateByUrl(`/sag-ps-pkg-dynamic-mapping/node3/extension/${this.app.id}`);
    this.router.navigate(['properties/', this.app.id], {
      relativeTo: this.activatedRoute
    });
    // console.log('Details clicked now:', this.app.id);
  }

  async delete() {
    const initialState = {
      title: 'Delete mapping extension',
      message:
        'You are about to delete a mapping extension. Do you want to proceed?',
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
    confirmDeletionModalRef.content.closeSubject.subscribe(
      async (confirmation: boolean) => {
        // console.log('Confirmation result:', confirmation);
        if (confirmation) {
          try {
            await this.extensionService.deleteExtension(this.app);
            this.appDeleted.emit();
          } catch (ex) {
            if (ex) {
              this.alertService.addServerFailure(ex);
            }
          }
        }
        confirmDeletionModalRef.hide();
      }
    );
  }
}
