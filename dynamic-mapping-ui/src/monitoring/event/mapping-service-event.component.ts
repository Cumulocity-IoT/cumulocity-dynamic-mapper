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
import { Component, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import {
  AlertService,
  LoadMoreComponent,
  Pagination
} from '@c8y/ngx-components';
import { BehaviorSubject, from, Observable, Subject, switchMap, takeUntil } from 'rxjs';
import {
  LoggingEventTypeMap,
  SharedService
} from '../../shared';
import { EventService, IEvent, IResultList } from '@c8y/client';

@Component({
  selector: 'd11r-mapping-service-event',
  templateUrl: 'mapping-service-event.component.html',
  styleUrls: ['../../mapping/shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MapppingServiceEventComponent implements OnInit, OnDestroy {

  baseFilter = {
    pageSize: 1000,
    withTotalPages: true,
    // type: LOCATION_UPDATE_EVENT_TYPE
  };

  pagination: Pagination = {
    pageSize: 5,
    currentPage: 1
  };
  mappingService: string;
  events$: Observable<IResultList<IEvent>>;
  loadMoreComponent: LoadMoreComponent;
  LoggingEventTypeMap = LoggingEventTypeMap;
  filterMappingServiceEvent = {type:'ALL'};
  filterSubject = new BehaviorSubject<void>(null);
  destroy$ = new Subject<void>();
  reload$ = new Subject<void>();

  constructor(
    public eventService: EventService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) { }

  ngOnInit() {
    this.events$ = this.filterSubject.pipe(
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

  onFilterMappingServiceEventSelect(event) {
    if (event == 'ALL'){
      delete this.baseFilter['type'];
    } else {
      this.baseFilter['type'] = LoggingEventTypeMap[event].type;
    }
    this.filterSubject.next();
  }

  onDateFromChange(date) {
    this.baseFilter['dateFrom'] = date.toISOString();
    this.filterSubject.next();
  }

  onDateToChange(date) {
    this.baseFilter['dateTo'] = date.toISOString();
    this.filterSubject.next();
  }
}
