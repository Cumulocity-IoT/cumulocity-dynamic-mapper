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
  ActionControl,
  AlertService,
  LoadMoreComponent,
  Pagination
} from '@c8y/ngx-components';
import { from, Observable, switchMap } from 'rxjs';
import {
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

  BASE_FILTER = {
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

  constructor(
    public eventService: EventService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) { }

  ngOnInit() {
    this.events$ = from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
      switchMap((mappingServiceId) => 
        this.eventService.list({
          ...this.BASE_FILTER,
          source: mappingServiceId,
        })
      )
    );
  }

  ngOnDestroy(): void {

  }
}
