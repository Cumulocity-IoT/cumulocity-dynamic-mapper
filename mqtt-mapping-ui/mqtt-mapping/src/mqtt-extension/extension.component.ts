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

import { Component, OnInit } from '@angular/core';
import { IManagedObject, IResultList } from '@c8y/client';
import { WizardService, WizardConfig } from '@c8y/ngx-components';
import { BehaviorSubject, Observable } from 'rxjs';
import { shareReplay, switchMap, tap } from 'rxjs/operators';
import { ExtensionService } from './extension.service';
import { ModalOptions } from 'ngx-bootstrap/modal';
import { BrokerConfigurationService } from '../mqtt-configuration/broker-configuration.service';
import { Operation } from '../shared/mapping.model';


@Component({
  selector: 'mapping-extension',
  templateUrl: './extension.component.html',
  styleUrls: ['./extension.component.css']
})
export class ExtensionComponent implements OnInit {
  reloading: boolean = false;
  reload$: BehaviorSubject<void> = new BehaviorSubject(null);
  externalExtensionEnabled: boolean = true;

  extensions$: Observable<IResultList<IManagedObject>> = this.reload$.pipe(
    tap(() => (this.reloading = true)),
    switchMap(() => this.extensionService.getExtensionsEnriched()),
    tap(console.log),
    tap(() => (this.reloading = false)),
    shareReplay()
  );

  listClass: string;

  constructor(
    private wizardService: WizardService,
    private extensionService: ExtensionService,
    private configurationService: BrokerConfigurationService
  ) { }

  async ngOnInit() {
    this.loadExtensions();
    this.extensions$.subscribe( exts => {
      console.log("New extenions:", exts);
    })
    this.externalExtensionEnabled = (await this.configurationService.getServiceConfiguration()).externalExtensionEnabled;
  }

  loadExtensions() {
    this.reload$.next();
  }

  async reloadExtensions() {
    await this.configurationService.runOperation(Operation.RELOAD_EXTENSIONS);
    this.reload$.next();
  }

  addExtension() {
    const wizardConfig: WizardConfig = {
      headerText: 'Add Extension Extension',
      headerIcon: 'plugin'
    };

    const initialState: any = {
      wizardConfig,
      id: 'uploadExtensionWizard'
    };

    const modalOptions: ModalOptions = { initialState };

    const modalRef = this.wizardService.show(modalOptions);
    modalRef.content.onClose.subscribe(() => {
      this.loadExtensions();
    });
  }
}
