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
import { BehaviorSubject, from, Observable, Subject, switchMap, takeUntil } from 'rxjs';
import {
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
      switchMap(() => from(this.sharedService.getDynamicMappingServiceAgent())),
      switchMap((mappingServiceId) =>
        this.eventService.list({
          ...this.baseFilter,
          source: mappingServiceId,
        })
      ),
      takeUntil(this.destroy$)
    );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.reload$.complete();
  }

  onFilterMappingServiceEventSelect(event: string): void {
    if (event === 'ALL') {
      delete this.baseFilter['type'];
    } else {
      this.baseFilter['type'] = LoggingEventTypeMap[event].type;
    }
    this.filterSubject$.next();
  }

  private onDateChange(field: 'dateFrom' | 'dateTo', date: Date): void {
    this.baseFilter[field] = date.toISOString();
    this.filterSubject$.next();
  }
}