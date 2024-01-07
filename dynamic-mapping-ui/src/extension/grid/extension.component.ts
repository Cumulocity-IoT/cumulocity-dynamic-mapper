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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { IManagedObject, IResultList } from '@c8y/client';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Observable } from 'rxjs';
import { shareReplay, switchMap, tap } from 'rxjs/operators';
import { ExtensionService } from '../share/extension.service';
import { BrokerConfigurationService, Operation } from '../../configuration';
import { AddExtensionComponent } from '../extension-modal/add-extension.component';

@Component({
  selector: 'd11r-mapping-extension',
  templateUrl: './extension.component.html',
  styleUrls: ['../share/extension.component.css']
})
export class ExtensionComponent implements OnInit, OnDestroy {
  reloading: boolean = false;
  reload$: BehaviorSubject<void> = new BehaviorSubject(null);
  externalExtensionEnabled: boolean = true;

  extensions$: Observable<IResultList<IManagedObject>>;

  listClass: string;

  constructor(
    private bsModalService: BsModalService,
    private extensionService: ExtensionService,
    private brokerConfigurationService: BrokerConfigurationService
  ) {}

  async ngOnInit() {
    this.extensions$ = this.reload$.pipe(
        tap(() => (this.reloading = true)),
        switchMap(() => this.extensionService.getExtensionsEnriched(undefined)),
        tap(console.log),
        tap(() => (this.reloading = false)),
        shareReplay()
      );
    this.loadExtensions();
    this.extensions$.subscribe((exts) => {
      console.log('New extenions:', exts);
    });
    this.externalExtensionEnabled = (
      await this.brokerConfigurationService.getServiceConfiguration()
    ).externalExtensionEnabled;
  }

  loadExtensions() {
    this.reload$.next();
  }

  async reloadExtensions() {
    await this.brokerConfigurationService.runOperation(
      Operation.RELOAD_EXTENSIONS
    );
    this.reload$.next();
  }

  addExtension() {
    const initialState = {};
    const modalRef = this.bsModalService.show(AddExtensionComponent, {
      initialState
    });
    modalRef.content.closeSubject.subscribe(() => {
      this.reloadExtensions();
      modalRef.hide();
      this.reload$.next();
    });
  }

  ngOnDestroy(): void {
    this.reload$.complete();
  }
}
