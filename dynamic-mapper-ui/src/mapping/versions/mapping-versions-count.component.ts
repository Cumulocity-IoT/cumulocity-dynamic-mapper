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
import {
  Component,
  inject,
  OnDestroy,
  OnInit,
  ViewEncapsulation
} from '@angular/core';
import {
  AlertService,
  Column,
  ColumnDataType,
  CommonModule,
  CoreModule,
  Pagination
} from '@c8y/ngx-components';
import { BehaviorSubject, firstValueFrom, Subject } from 'rxjs';
import { Router } from '@angular/router';
import { Direction } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { NumberRendererComponent } from '../../monitoring/renderer/number.renderer.component';
import { NameRendererComponent } from '../renderer/name.renderer.component';
import { VersionBadgeRendererComponent } from '../renderer/version-badge.renderer.component';

interface VersionCountRow {
  id: string;
  name: string;
  identifier: string;
  topic: string;
  activeVersion: string;
  versionCount: number;
}

@Component({
  selector: 'd11r-mapping-versions-count',
  templateUrl: './mapping-versions-count.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule]
})
export class MappingVersionsCountComponent implements OnInit, OnDestroy {
  private readonly mappingService = inject(MappingService);
  private readonly alertService = inject(AlertService);
  private readonly router = inject(Router);
  private readonly destroy$ = new Subject<void>();

  readonly rows$ = new BehaviorSubject<VersionCountRow[]>([]);
  readonly isLoading$ = new BehaviorSubject<boolean>(false);

  direction: Direction;
  title: string;
  columns: Column[] = [];

  readonly pagination: Pagination = { pageSize: 30, currentPage: 1 };

  async ngOnInit(): Promise<void> {
    this.direction = this.router.url.includes('/monitoring/versions/inbound')
      ? Direction.INBOUND
      : Direction.OUTBOUND;
    this.title = `Versions ${this.direction.toLowerCase()}`;
    this.buildColumns();
    await this.loadRows();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  async refresh(): Promise<void> {
    this.mappingService.clearVersionsCache();
    await this.loadRows();
  }

  private async loadRows(): Promise<void> {
    this.isLoading$.next(true);
    try {
      const [enriched, counts] = await Promise.all([
        firstValueFrom(this.mappingService.getMappingsObservable(this.direction)),
        this.mappingService.getVersionCounts(this.direction)
      ]);

      const countById = new Map(counts.map(c => [c.id, c.versionCount]));

      const rows: VersionCountRow[] = enriched.map(e => ({
        id: e.mapping.id,
        name: e.mapping.name,
        identifier: e.mapping.identifier,
        topic: this.direction === Direction.INBOUND
          ? (e.mapping.mappingTopic ?? '—')
          : (e.mapping.publishTopic ?? '—'),
        activeVersion: e.mapping.version ?? '—',
        versionCount: countById.get(e.mapping.id) ?? 0
      }));

      this.rows$.next(rows);
    } catch (err) {
      this.alertService.danger('Failed to load version counts', (err as Error).message);
    } finally {
      this.isLoading$.next(false);
    }
  }

  private buildColumns(): void {
    this.columns = [
      {
        name: 'name',
        header: 'Name',
        path: 'name',
        filterable: false,
        sortOrder: 'asc',
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: NameRendererComponent,
        gridTrackSize: '30%',
        visible: true
      },
      {
        name: 'topic',
        header: this.direction === Direction.INBOUND ? 'Mapping topic' : 'Publish topic',
        path: 'topic',
        filterable: false,
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'activeVersion',
        header: 'Active version',
        path: 'activeVersion',
        filterable: false,
        cellRendererComponent: VersionBadgeRendererComponent,
        dataType: ColumnDataType.Numeric,
        gridTrackSize: '15%'
      },
      {
        name: 'versionCount',
        header: 'Versions',
        path: 'versionCount',
        filterable: false,
        cellRendererComponent: NumberRendererComponent,
        dataType: ColumnDataType.Numeric,
        gridTrackSize: '12%'
      }
    ];
  }
}
