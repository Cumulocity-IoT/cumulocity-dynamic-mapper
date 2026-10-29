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
import { ActivatedRoute } from '@angular/router';
import { EventService, IEvent, IResultList, InventoryService } from '@c8y/client';
import { CoreModule, EventRealtimeService, Pagination, RealtimeButtonComponent, RealtimeSubjectService } from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import { BsDatepickerModule } from 'ngx-bootstrap/datepicker';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { filter } from 'rxjs/operators';
import { BehaviorSubject, catchError, shareReplay, combineLatest, EMPTY, from, map, Observable, of, scan, startWith, Subject, switchMap, takeUntil, tap } from 'rxjs';
import {
  ConnectorStatusHistory,
  ConnectorStatusHistoryComponent,
  EventMetadata,
  getEventMetadata as getEventMetadataShared,
  getEventSubject,
  getSeverityBadgeClass,
  LoggingEventType,
  LoggingEventTypeMap,
  SharedModule,
  SharedService
} from '../../shared';

@Component({
  selector: 'd11r-mapping-service-event',
  templateUrl: 'mapping-service-event.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, SharedModule, BsDatepickerModule, BsDropdownModule, ReactiveFormsModule, ConnectorStatusHistoryComponent, RealtimeButtonComponent]
})
export class MappingServiceEventComponent implements OnInit, OnDestroy {

  constructor(
    private eventService: EventService,
    private inventoryService: InventoryService,
    private sharedService: SharedService,
    private fb: FormBuilder,
    private route: ActivatedRoute,
    realtimeSubjectService: RealtimeSubjectService
  ) {
    this.eventRealtimeService = new EventRealtimeService(realtimeSubjectService);
    this.createForm();
  }

  readonly eventRealtimeService: EventRealtimeService;

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
  /** Distinct subjects (connector, mapping, cache, ...) in the loaded page, for the subject select. */
  subjectOptions$: Observable<string[]>;

  /** Event types grouped by component, for the grouped event-type select. */
  readonly typeGroups: { component: string; types: string[] }[] = (() => {
    const groups = new Map<string, string[]>();
    for (const [key, details] of Object.entries(LoggingEventTypeMap)) {
      if (key === LoggingEventType.ALL) {
        continue;
      }
      const name = details.componentDisplayName ?? 'Other';
      groups.set(name, [...(groups.get(name) ?? []), key]);
    }
    return [...groups].map(([component, types]) => ({ component, types }));
  })();
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
      dateTo: new FormControl(null),
      onlyOpenSessions: new FormControl(false),
      onlyErrors: new FormControl(false),
      severity: new FormControl('ALL'),
      subject: new FormControl('ALL'),
      search: new FormControl('')
    });
  }

  ngOnInit(): void {
    this.setupFormSubscriptions();
    this.setupEventsObservable();
    this.eventRealtimeService.reconnect$
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.filterSubject$.next());

    // Pre-select filter from query param (e.g. when navigated from subscription page)
    const typeParam = this.route.snapshot.queryParamMap.get('type');
    if (typeParam && typeParam in LoggingEventType) {
      this.filterForm.get('type').setValue(typeParam, { emitEvent: false });
      this.onFilterMappingServiceEventSelect(typeParam);
    } else {
      // Trigger initial load with default filter
      this.filterSubject$.next();
    }
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
    const fetched$ = this.filterSubject$.pipe(
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
      // Live updates: fold each pushed event into the freshly fetched page. A new fetch
      // (filter change, reload) restarts the fold from that page.
      switchMap(result => this.liveEvents$().pipe(
        scan((acc, event) => this.upsertEvent(acc, event), result),
        startWith(result)
      )),
      takeUntil(this.destroy$),
      // events$, componentOptions$ and connectorOptions$ all derive from this; share it so the
      // template's multiple async pipes don't each trigger their own fetch.
      shareReplay({ bufferSize: 1, refCount: true })
    );

    this.subjectOptions$ = fetched$.pipe(
      map(result => this.distinct((result.data ?? []).map(e => this.getSubject(e)).filter(Boolean)))
    );

    // These filters are applied client-side on top of the already-fetched page: they're
    // properties of unindexed fragments (d11r_connectorStatusLog, d11r_connector) or derived
    // display names, not something the event API can filter by, and re-fetching for a purely
    // local toggle would be wasteful.
    const localFilters$ = this.filterForm.valueChanges.pipe(
      startWith(this.filterForm.getRawValue()),
      map(() => this.filterForm.getRawValue()),
      takeUntil(this.destroy$)
    );

    this.events$ = combineLatest([fetched$, localFilters$]).pipe(
      map(([result, f]) => {
        const search = (f.search ?? '').trim().toLowerCase();
        const data = (result.data ?? []).filter(event => {
          if (f.onlyOpenSessions && this.getConnectorStatusHistory(event)?.sessionClosed !== false) {
            return false;
          }
          if (f.onlyErrors && !this.getConnectorStatusHistory(event)?.hadError) {
            return false;
          }
          if (f.severity !== 'ALL' && this.getSeverity(event) !== f.severity) {
            return false;
          }
          if (f.subject !== 'ALL' && this.getSubject(event) !== f.subject) {
            return false;
          }
          return !search || (event.text ?? '').toLowerCase().includes(search)
            || (this.getSubject(event) ?? '').toLowerCase().includes(search);
        });
        const localFilterActive = !!search || f.onlyOpenSessions || f.onlyErrors
          || f.severity !== 'ALL' || f.subject !== 'ALL';
        // c8yFor's auto load-more follows paging.nextPage and appends the raw server page, which
        // would bypass the filter above (a short filtered list never fills the viewport, so it
        // keeps loading). While a local filter is active, report the page as the last one.
        const paging = localFilterActive && result.paging
          ? { ...result.paging, nextPage: null, totalPages: result.paging.currentPage }
          : result.paging;
        return { ...result, data, paging } as IResultList<IEvent>;
      })
    );
  }

  private distinct(values: string[]): string[] {
    return [...new Set(values)].sort((a, b) => a.localeCompare(b));
  }

  getSubject(event: IEvent): string | null {
    return getEventSubject(event);
  }

  getSeverity(event: IEvent): string {
    return getEventMetadataShared(event)?.severity ?? 'info';
  }

  /** Events pushed by the backend that match the current filter. Silent while realtime is paused. */
  private liveEvents$(): Observable<IEvent> {
    return from(this.sharedService.getDynamicMappingServiceAgent()).pipe(
      switchMap(agentId => this.eventRealtimeService.onAll$(agentId)),
      map(message => message.data as IEvent),
      filter(event => this.matchesCurrentFilter(event)),
      catchError(error => {
        console.error('Realtime service events failed:', error);
        return EMPTY;
      })
    );
  }

  private matchesCurrentFilter(event: IEvent): boolean {
    const { type, dateFrom, dateTo } = this.baseFilter as { type?: string; dateFrom?: string; dateTo?: string };
    const time = new Date(event.time).getTime();
    return (!type || event.type === type)
      && (!dateFrom || time >= new Date(dateFrom).getTime())
      && (!dateTo || time <= new Date(dateTo).getTime());
  }

  /** Replaces an already listed event (sessions are updated in place) or prepends a new one. */
  private upsertEvent(result: IResultList<IEvent>, event: IEvent): IResultList<IEvent> {
    const data = result.data ?? [];
    const index = data.findIndex(e => e.id === event.id);
    const next = index >= 0
      ? data.map((e, i) => (i === index ? event : e))
      : [event, ...data];
    return { ...result, data: next } as IResultList<IEvent>;
  }

  onRealtimeToggle(active: boolean): void {
    // Events pushed while realtime was paused were not received; catch up.
    if (active) {
      this.filterSubject$.next();
    }
  }

  ngOnDestroy(): void {
    this.eventRealtimeService.stop();
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

  /** Number of filters set inside the "Filters" dropdown, shown as a badge on its button. */
  get activeAdvancedFilterCount(): number {
    const f = this.filterForm.getRawValue();
    return [
      f.subject !== 'ALL',
      !!f.dateFrom,
      !!f.dateTo,
      f.onlyOpenSessions,
      f.onlyErrors
    ].filter(Boolean).length;
  }

  resetFilters(): void {
    this.filterForm.patchValue({
      type: 'ALL',
      dateFrom: null,
      dateTo: null,
      onlyOpenSessions: false,
      onlyErrors: false,
      severity: 'ALL',
      subject: 'ALL',
      search: ''
    });
    delete this.baseFilter['type'];
    delete this.baseFilter['dateFrom'];
    delete this.baseFilter['dateTo'];
    this.filterSubject$.next();
  }

  getEventMetadata(event: IEvent): EventMetadata | null {
    return getEventMetadataShared(event);
  }

  getSeverityClass(severity: string): string {
    return getSeverityBadgeClass(severity);
  }

  extractManagedObjectId(event: IEvent): string | null {
    return event?.['d11r_system']?.['id'] ?? null;
  }

  /**
   * Returns the bundled connection-lifecycle session for a connector-status event, or null for
   * events that don't carry one (other event types, or events created before this fragment
   * existed) — callers fall back to the raw-JSON view for those, see the template.
   */
  getConnectorStatusHistory(event: IEvent): ConnectorStatusHistory | null {
    return event?.['d11r_connectorStatusLog'] ?? null;
  }

  async downloadMapping(event: IEvent): Promise<void> {
    const moId = this.extractManagedObjectId(event);
    if (!moId) return;
    const { data } = await this.inventoryService.detail(moId);
    const mapping = data['d11r_mapping'];
    const json = JSON.stringify(mapping ? [mapping] : [data], undefined, 2);
    const blob = new Blob([json], { type: 'application/json' });
    saveAs(blob, `mapping-${moId}.json`);
  }

  async downloadMappingMo(event: IEvent): Promise<void> {
    const moId = this.extractManagedObjectId(event);
    if (!moId) return;
    const { data } = await this.inventoryService.detail(moId);
    const json = JSON.stringify(data, undefined, 2);
    const blob = new Blob([json], { type: 'application/json' });
    saveAs(blob, `mo-mapping-${moId}.json`);
  }

  private onDateChange(field: 'dateFrom' | 'dateTo', date: Date): void {
    this.baseFilter[field] = date.toISOString();
    this.filterSubject$.next();
  }
}