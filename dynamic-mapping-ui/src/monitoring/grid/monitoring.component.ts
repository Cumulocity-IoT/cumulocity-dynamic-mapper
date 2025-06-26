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
import { Component, inject, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import {
  ActionControl,
  AlertService,
  Column,
  ColumnDataType,
  DisplayOptions,
  gettext,
  Pagination
} from '@c8y/ngx-components';
import { BehaviorSubject, catchError, map, of, Subject, takeUntil } from 'rxjs';
import {
  ConfirmationModalComponent,
  Feature,
  MappingStatus,
  Operation,
  SharedService
} from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';
import { NumberRendererComponent } from '../renderer/number.renderer.component';
import { DirectionRendererComponent } from '../renderer/direction.renderer.component';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { NameRendererComponent } from '../../mapping/renderer/name.renderer.component';
import { ActivatedRoute } from '@angular/router';

interface MonitoringComponentState {
  mappingStatuses: MappingStatus[];
  isLoading: boolean;
  error: string | null;
}
@Component({
  selector: 'd11r-mapping-monitoring-grid',
  templateUrl: 'monitoring.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MonitoringComponent implements OnInit, OnDestroy {

  // Modern Angular dependency injection
  private readonly monitoringService = inject(MonitoringService);
  private readonly alertService = inject(AlertService);
  private readonly bsModalService = inject(BsModalService);
  private readonly sharedService = inject(SharedService);
  private readonly route = inject(ActivatedRoute);


  // Subscription management
  private readonly destroy$ = new Subject<void>();

  // State management
  readonly state$ = new BehaviorSubject<MonitoringComponentState>({
    mappingStatuses: [],
    isLoading: false,
    error: null
  });


  readonly mappingStatus$ = this.state$.pipe(
    map(state => state.mappingStatuses))
  readonly isLoading$ = this.state$.pipe(map(state => state.isLoading));
  readonly error$ = this.state$.pipe(map(state => state.error));

  readonly displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  readonly columns: Column[] = [
    {
      name: 'name',
      header: 'Name',
      path: 'name',
      filterable: false,
      sortOrder: 'asc',
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: NameRendererComponent,
      visible: true
    },
    {
      name: 'direction',
      header: 'Direction',
      path: 'direction',
      filterable: false,
      dataType: ColumnDataType.Icon,
      cellRendererComponent: DirectionRendererComponent,
      visible: true
    },
    {
      name: 'mappingTopic',
      header: 'Mapping topic',
      path: 'mappingTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '15%'
    },
    {
      name: 'publishTopic',
      header: 'Publish topic',
      path: 'publishTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '15%'
    },
    {
      header: '# Errors',
      name: 'errors',
      path: 'errors',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Messages received',
      name: 'messagesReceived',
      path: 'messagesReceived',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Snooped templates total',
      name: 'snoopedTemplatesTotal',
      path: 'snoopedTemplatesTotal',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Snooped templates active',
      name: 'snoopedTemplatesActive',
      path: 'snoopedTemplatesActive',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    },
    {
      header: '# Current failure count',
      name: 'currentFailureCount',
      path: 'currentFailureCount',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      cellRendererComponent: NumberRendererComponent,
      gridTrackSize: '10%'
    }
  ] as const;

  readonly pagination: Pagination = {
    pageSize: 5,
    currentPage: 1
  };

  feature: Feature;
  actionControls: ActionControl[] = [];


  async ngOnInit(): Promise<void> {
    try {
      this.updateState({ isLoading: true, error: null });

      this.feature = this.route.snapshot.data['feature'];
      await this.initializeMonitoringService();

      this.updateState({ isLoading: false });
    } catch (error) {
      this.handleError('Failed to initialize monitoring', error);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.monitoringService.stopMonitoring();
  }

  async refreshStatisticsMapping(): Promise<void> {
    try {
      this.updateState({ isLoading: true, error: null });

      await this.sharedService.runOperation({
        operation: Operation.REFRESH_STATUS_MAPPING
      });

      this.alertService.success(
        gettext('Mapping status refreshed successfully.')
      );
    } catch (error) {
      this.handleError('Failed to refresh mapping status', error);
    } finally {
      this.updateState({ isLoading: false });
    }
  }

  async resetStatisticsMapping(): Promise<void> {
    const modalRef = this.showConfirmationModal();

    modalRef.content.closeSubject
      .pipe(takeUntil(this.destroy$))
      .subscribe(async (confirmed: boolean) => {
        if (confirmed) {
          await this.performReset();
        }
        modalRef.hide();
      });
  }

  private async initializeMonitoringService(): Promise<void> {
    await this.monitoringService.startMonitoring();

    this.monitoringService
      .getMappingStatus()
      .pipe(
        takeUntil(this.destroy$),
        catchError(error => {
          this.handleError('Failed to get mapping status', error);
          return of([]);
        })
      )
      .subscribe(statuses => {
        this.updateState({
          mappingStatuses: statuses,
          error: null
        });
      });
  }
  private showConfirmationModal(): BsModalRef {
    const initialState = {
      title: 'Reset mapping statistic',
      message: 'You are about to delete the mapping statistic. Do you want to proceed?',
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    };

    return this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
  }

  private async performReset(): Promise<void> {
    try {
      this.updateState({ isLoading: true });

      const response = await this.sharedService.runOperation({
        operation: Operation.RESET_STATISTICS_MAPPING
      });

      if (response.status >= 200 && response.status < 300) {
        this.alertService.success(
          gettext('Mapping statistic reset successfully.')
        );
      } else {
        throw new Error(`Reset failed with status: ${response.status}`);
      }

      await this.sharedService.runOperation({
        operation: Operation.REFRESH_STATUS_MAPPING
      });
    } catch (error) {
      this.handleError('Failed to reset mapping statistic', error);
    } finally {
      this.updateState({ isLoading: false });
    }
  }


  private updateState(partialState: Partial<MonitoringComponentState>): void {
    const currentState = this.state$.value;
    this.state$.next({ ...currentState, ...partialState });
  }

  private handleError(message: string, error: unknown): void {
    console.error(message, error);

    const errorMessage = error instanceof Error
      ? error.message
      : 'Unknown error occurred';

    this.updateState({
      error: errorMessage,
      isLoading: false
    });

    this.alertService.danger(gettext(message));
  }

}