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
import { Component, inject, Input, OnInit, ViewEncapsulation } from '@angular/core';
import {
  ActionControl,
  AlertService,
  BottomDrawerRef,
  Column,
  ColumnDataType,
  CoreModule,
  Pagination
} from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject, take } from 'rxjs';
import { ConfirmationModalComponent, Mapping, SharedModule } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { LabelInputModalComponent } from './label-input-modal.component';
import { VersionStateCellRendererComponent } from './version-state-cell.renderer.component';

type VersionState = 'active' | 'published' | 'draft';

/** One row of the versions grid: a published version, or the single draft. */
interface VersionRow {
  id: string;
  versionNumber: number;
  versionDisplay: string;
  state: VersionState;
  label: string;
  updatedDisplay: string;
  createdBy: string;
  isDraft: boolean;
}

const DRAFT_ROW_ID = '__draft__';

/**
 * Bottom drawer listing all records of a mapping line in a single c8y-data-grid:
 * every published version plus the current draft, each tagged with a State
 * (active / published / draft). Row actions are contextual — Publish on the draft,
 * Activate / Delete on inactive published versions. The active version (the one
 * whose number matches the mapping's {@code versionNumber}) has no actions.
 */
@Component({
  selector: 'd11r-mapping-version-drawer',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: './mapping-version-drawer.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, SharedModule]
})
export class MappingVersionDrawerComponent implements OnInit {
  @Input() mapping: Mapping;
  /** Whether the current user may change versions (activate/publish/delete). */
  @Input() canManage = true;

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly mappingService = inject(MappingService);
  private readonly alertService = inject(AlertService);
  private readonly bsModalService = inject(BsModalService);

  /** Emits true if anything changed, so the opener can refresh the mapping grid. */
  closeSubject = new Subject<boolean>();

  columns: Column[] = this.buildColumns();
  actionControls: ActionControl[] = this.buildActionControls();
  // c8y-data-grid reacts to an observable of rows (like the main mappings grid),
  // not to a plain array reassigned asynchronously.
  readonly rows$ = new BehaviorSubject<VersionRow[]>([]);
  readonly pagination: Pagination = { pageSize: 100, currentPage: 1 };
  loading = true;
  busy = false;
  private changed = false;

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.mappingService.clearVersionsCache(this.mapping.id);
    this.loading = true;
    try {
      const [versions, draft] = await Promise.all([
        this.mappingService.getVersions(this.mapping.id),
        this.mappingService.getDraft(this.mapping.id)
      ]);

      const versionRows: VersionRow[] = (versions ?? [])
        .sort((a, b) => b.versionNumber - a.versionNumber)
        .map(v => ({
          id: v.id ?? `v${v.versionNumber}`,
          versionNumber: v.versionNumber,
          versionDisplay: `v${v.versionNumber}`,
          state: (v.versionNumber === this.mapping.versionNumber ? 'active' : 'published') as VersionState,
          label: v.label || '—',
          updatedDisplay: v.createdAt ? new Date(v.createdAt).toLocaleString() : '—',
          createdBy: v.createdBy || '—',
          isDraft: false
        }));

      // The draft is shown as its own row at the top, so the whole history lives in one grid.
      const draftRow: VersionRow[] = draft
        ? [{
          id: DRAFT_ROW_ID,
          versionNumber: 0,
          versionDisplay: '—',
          state: 'draft',
          label: draft.versionLabel || '—',
          updatedDisplay: draft.lastUpdate ? new Date(draft.lastUpdate).toLocaleString() : '—',
          createdBy: '—',
          isDraft: true
        }]
        : [];

      this.rows$.next([...draftRow, ...versionRows]);
    } catch (e) {
      this.alertService.danger('Failed to load versions', (e as Error).message);
    } finally {
      this.loading = false;
    }
  }

  async activate(row: VersionRow): Promise<void> {
    this.busy = true;
    try {
      await this.mappingService.activateVersion(this.mapping.id, row.versionNumber);
      this.alertService.success(`Activated version ${row.versionNumber} of ${this.mapping.name}`);
      this.mapping.versionNumber = row.versionNumber;
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to activate version', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  async remove(row: VersionRow): Promise<void> {
    const confirmed = await this.confirmDelete(row);
    if (!confirmed) {
      return;
    }
    this.busy = true;
    try {
      await this.mappingService.deleteVersion(this.mapping.id, row.versionNumber);
      this.alertService.success(`Deleted version ${row.versionNumber} of ${this.mapping.name}`);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to delete version', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  /** Confirms deletion of a version; resolves true if the user proceeds. */
  private confirmDelete(row: VersionRow): Promise<boolean> {
    return new Promise(resolve => {
      const ref = this.bsModalService.show(ConfirmationModalComponent, {
        initialState: {
          title: `Delete version ${row.versionDisplay}`,
          message: `You are about to permanently delete ${row.versionDisplay} of ${this.mapping.name}. This cannot be undone. Do you want to proceed?`,
          labels: { ok: 'Delete', cancel: 'Cancel' }
        }
      });
      ref.content.closeSubject.pipe(take(1)).subscribe((result: boolean) => {
        resolve(!!result);
        ref.hide();
      });
    });
  }

  async publish(): Promise<void> {
    const label = await this.promptLabel('Publish draft as new version', '');
    if (label === undefined) {
      return; // cancelled
    }
    this.busy = true;
    try {
      const version = await this.mappingService.publishDraft(this.mapping.id, label || undefined);
      this.alertService.success(`Published version ${version.versionNumber} of ${this.mapping.name}`);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to publish draft', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  async editLabel(row: VersionRow): Promise<void> {
    const label = await this.promptLabel(`Edit label of v${row.versionNumber}`, row.label === '—' ? '' : row.label);
    if (label === undefined) {
      return; // cancelled
    }
    this.busy = true;
    try {
      await this.mappingService.updateVersionLabel(this.mapping.id, row.versionNumber, label);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to update label', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  /** Opens the label input modal, resolving to the entered text or undefined if cancelled. */
  private promptLabel(title: string, value: string): Promise<string | undefined> {
    return new Promise(resolve => {
      const ref = this.bsModalService.show(LabelInputModalComponent, { initialState: { title, value } });
      ref.content.closeSubject.pipe(take(1)).subscribe((result: string | undefined) => {
        resolve(result);
        ref.hide();
      });
    });
  }

  close(): void {
    this.closeSubject.next(this.changed);
    this.closeSubject.complete();
    this.bottomDrawerRef.close();
  }

  private buildColumns(): Column[] {
    // No explicit gridTrackSize: let the grid auto-distribute and reserve space for
    // the row-actions (⋮) column, otherwise the actions are pushed off-screen.
    return [
      {
        name: 'state',
        header: 'State',
        path: 'state',
        dataType: ColumnDataType.TextShort,
        gridTrackSize: '7%',
        cellRendererComponent: VersionStateCellRendererComponent
      },
      {
        name: 'versionDisplay',
        header: 'Version',
        path: 'versionDisplay',
        gridTrackSize: '7%',
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'label',
        header: 'Label',
        path: 'label',
        gridTrackSize: '40%',
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'updatedDisplay',
        header: 'Updated',
        path: 'updatedDisplay',
        gridTrackSize: '17.5%',
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'createdBy',
        header: 'By',
        path: 'createdBy',
        gridTrackSize: '21.5%',
        dataType: ColumnDataType.TextShort
      }
    ];
  }

  private buildActionControls(): ActionControl[] {
    return [
      {
        type: 'PUBLISH',
        text: 'Publish',
        icon: 'upload',
        callback: () => this.publish(),
        showIf: (row: VersionRow) => this.canManage && row.isDraft && !this.busy
      },
      {
        type: 'EDIT_LABEL',
        text: 'Edit label',
        icon: 'pencil',
        callback: (row: VersionRow) => this.editLabel(row),
        showIf: (row: VersionRow) => this.canManage && !row.isDraft && !this.busy
      },
      {
        type: 'ACTIVATE',
        text: 'Activate',
        icon: 'toggle-on',
        callback: (row: VersionRow) => this.activate(row),
        showIf: (row: VersionRow) => this.canManage && !row.isDraft && row.state !== 'active' && !this.busy
      },
      {
        type: 'DELETE_VERSION',
        text: 'Delete',
        icon: 'trash-o',
        callback: (row: VersionRow) => this.remove(row),
        showIf: (row: VersionRow) => this.canManage && !row.isDraft && row.state !== 'active' && !this.busy
      }
    ];
  }
}
