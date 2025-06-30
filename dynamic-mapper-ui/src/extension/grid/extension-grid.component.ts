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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { IManagedObject } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { catchError, retry, shareReplay, switchMap, takeUntil, tap, timeout } from 'rxjs/operators';
import { Feature, Operation, SharedService } from '../../shared';
import { AddExtensionComponent } from '../add/add-extension-modal.component';
import { ExtensionService } from '../extension.service';

interface ExtensionState {
  reloading: boolean;
  feature?: Feature;
  externalExtensionEnabled: boolean;
}

@Component({
  selector: 'd11r-mapping-extension',
  templateUrl: './extension-grid.component.html',
  styleUrls: ['../share/extension.component.css'],
  standalone: false
})
export class ExtensionGridComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private readonly reload$ = new BehaviorSubject<void>(null);
  private readonly state = new BehaviorSubject<ExtensionState>({
    reloading: false,
    externalExtensionEnabled: true
  });

  
  readonly state$ = this.state.asObservable();
  extensions$: Observable<IManagedObject[]>;

  get reloading(): boolean {
    return this.state.value.reloading;
  }

  get feature(): Feature {
    return this.state.value.feature;
  }

  get externalExtensionEnabled(): boolean {
    return this.state.value.externalExtensionEnabled;
  }

  constructor(
    private bsModalService: BsModalService,
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private sharedService: SharedService
  ) { }

  async ngOnInit(): Promise<void> {
    try {
      await this.initializeComponent();
    } catch (error) {
      this.alertService.warning('Failed to initialize component', error);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.reload$.complete();
    this.state.complete();
  }

  async loadExtensions(): Promise<void> {
    this.reload$.next();
  }

  async reloadExtensions(): Promise<void> {
    try {
      await this.sharedService.runOperation({ operation: Operation.RELOAD_EXTENSIONS });
      this.alertService.success('Extensions reloaded');
      await this.loadExtensions();
    } catch (error) {
      this.alertService.warning('Failed to reload extensions', error);
    }
  }

  async addExtension(): Promise<void> {
    try {
      const modalRef = this.showAddExtensionModal();
      this.handleModalClose(modalRef);
    } catch (error) {
      this.alertService.warning('Failed to add extension', error);
    }
  }

  private async initializeComponent(): Promise<void> {
    await this.initializeFeatures();
    this.initializeExtensionsStream();
    await this.loadExtensions();
  }

  private async initializeFeatures(): Promise<void> {
    const [features, config] = await Promise.all([
      this.sharedService.getFeatures(),
      this.sharedService.getServiceConfiguration()
    ]);

    this.updateState({
      feature: features,
      externalExtensionEnabled: config.externalExtensionEnabled
    });
  }


  private initializeExtensionsStream(): void {
    this.extensions$ = this.reload$.pipe(
      tap(() => this.updateState({ reloading: true })),
      switchMap(() => this.loadExtensionsWithRetry()),
      tap(() => this.updateState({ reloading: false })),
      catchError(error => {
        this.alertService.warning('Failed to load extensions', error);
        return [];
      }),
      shareReplay(1),
      takeUntil(this.destroy$)
    );

    // Initialize subscription
    this.extensions$.subscribe();
  }

  private loadExtensionsWithRetry(): Observable<IManagedObject[]> {
    return this.extensionService.getEnrichedProcessorExtensions(undefined).pipe(
      retry(3),
      timeout(10000)
    );
  }

  private showAddExtensionModal(): BsModalRef {
    return this.bsModalService.show(AddExtensionComponent, {
      initialState: {}
    });
  }

  private handleModalClose(modalRef: BsModalRef): void {
    modalRef.content.closeSubject
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: async () => {
          await this.reloadExtensions();
          modalRef.hide();
        },
        error: error => this.alertService.warning('Modal close error', error)
      });
  }

  private updateState(partialState: Partial<ExtensionState>): void {
    this.state.next({
      ...this.state.value,
      ...partialState
    });
  }

}
