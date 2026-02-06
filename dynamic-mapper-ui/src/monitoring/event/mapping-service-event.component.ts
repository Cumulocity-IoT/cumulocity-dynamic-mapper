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
import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EventService, IEvent, IResultList } from '@c8y/client';
import { CoreModule, Pagination } from '@c8y/ngx-components';
import { BsDatepickerModule } from 'ngx-bootstrap/datepicker';
import { BehaviorSubject, catchError, from, Observable, of, Subject, switchMap, takeUntil, tap } from 'rxjs';
import {
  EventMetadata,
  LoggingEventTypeMap,
  SharedModule,
  SharedService
} from '../../shared';

@Component({
  selector: 'd11r-mapping-service-event',
  templateUrl: 'mapping-service-event.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, SharedModule, BsDatepickerModule, ReactiveFormsModule]
})
export class MappingServiceEventComponent implements OnInit, OnDestroy {

  constructor(
    private eventService: EventService,
    private sharedService: SharedService,
    private fb: FormBuilder
  ) {
    this.createForm();
  }

  readonly baseFilter = {
    pageSize: 1000,
    withTotalPages: true,
    // type: LOCATION_UPDATE_EVENT_TYPE
  };

  readonly pagination: Pagination = {
    pageSize: 5,
    currentPage: 1
  };

  // Reactive Form
  filterForm: FormGroup;

  events$: Observable<IResultList<IEvent>>;
  LoggingEventTypeMap = LoggingEventTypeMap;
  filterSubject$ = new BehaviorSubject<void>(null);
  destroy$ = new Subject<void>();
  reload$ = new Subject<void>();

  // State management
  readonly isLoading$ = new BehaviorSubject<boolean>(true);
  readonly error$ = new BehaviorSubject<string | null>(null);

  private createForm(): void {
    this.filterForm = this.fb.group({
      type: new FormControl('ALL'),
      dateFrom: new FormControl(null),
      dateTo: new FormControl(null)
    });
  }

  ngOnInit(): void {
    this.setupFormSubscriptions();
    this.setupEventsObservable();

    // Trigger initial load
    this.filterSubject$.next();
  }

  private setupFormSubscriptions(): void {
    this.filterForm.get('type')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(type => {
        this.onFilterMappingServiceEventSelect(type);
      });

    this.filterForm.get('dateFrom')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(date => {
        if (date) {
          this.onDateChange('dateFrom', date);
        }
      });

    this.filterForm.get('dateTo')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(date => {
        if (date) {
          this.onDateChange('dateTo', date);
        }
      });
  }

  private setupEventsObservable(): void {
    this.events$ = this.filterSubject$.pipe(
      tap(() => {
        this.isLoading$.next(true);
        this.error$.next(null);
      }),
      switchMap(() => from(this.sharedService.getDynamicMappingServiceAgent())),
      switchMap((mappingServiceId) =>
        this.eventService.list({
          ...this.baseFilter,
          source: mappingServiceId,
        })
      ),
      tap(() => this.isLoading$.next(false)),
      catchError(error => {
        console.error('Error loading service events:', error);
        this.isLoading$.next(false);
        this.error$.next('Failed to load service events. Please try again.');
        return of({ data: [], paging: {} } as IResultList<IEvent>);
      }),
      takeUntil(this.destroy$)
    );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.reload$.complete();
    this.isLoading$.complete();
    this.error$.complete();
  }

  onFilterMappingServiceEventSelect(event: string): void {
    if (!event) {
      return;
    }

    if (event === 'ALL') {
      delete this.baseFilter['type'];
    } else {
      const eventType = LoggingEventTypeMap[event];
      if (eventType?.type) {
        this.baseFilter['type'] = eventType.type;
      }
    }
    this.filterSubject$.next();
  }

  resetFilters(): void {
    this.filterForm.patchValue({
      type: 'ALL',
      dateFrom: null,
      dateTo: null
    });
    delete this.baseFilter['type'];
    delete this.baseFilter['dateFrom'];
    delete this.baseFilter['dateTo'];
    this.filterSubject$.next();
  }

  getEventMetadata(event: IEvent): EventMetadata | null {
    // Try to get metadata from event first (new events with d11r_metadata fragment)
    const metadata = event['d11r_metadata'];
    if (metadata) {
      return metadata as EventMetadata;
    }

    // Fallback: lookup for old events without metadata
    const entry = Object.entries(LoggingEventTypeMap).find(
      ([_, details]) => details.type === event.type
    );
    if (entry && entry[1]) {
      return {
        component: entry[1].component || '',
        componentDisplayName: entry[1].componentDisplayName || 'Unknown',
        severity: entry[1].severity || 'info',
        description: entry[1].description || ''
      };
    }
    return null;
  }

  getSeverityClass(severity: string): string {
    switch (severity) {
      case 'error': return 'label-danger';
      case 'warning': return 'label-warning';
      case 'info':
      default: return 'label-primary';
    }
  }

  private onDateChange(field: 'dateFrom' | 'dateTo', date: Date): void {
    this.baseFilter[field] = date.toISOString();
    this.filterSubject$.next();
  }
}