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
import { NoteEditCellRendererComponent } from './note-edit-cell-renderer.component';
import { VersionStateCellRendererComponent } from './version-state-cell.renderer.component';

type VersionState = 'active' | 'published' | 'draft';

/** One row of the versions grid: a published version, or the single draft. */
interface VersionRow {
  id: string;
  versionNumber: number;
  versionDisplay: string;
  state: VersionState;
  note: string;
  updatedDisplay: string;
  createdBy: string;
  isDraft: boolean;
  /** Injected per-row by the drawer. Absent when canManage=false (makes the cell read-only). */
  onNoteChange?: (note: string) => void;
}

const DRAFT_ROW_ID = '__draft__';

/**
 * Bottom drawer listing all records of a mapping line in a single c8y-data-grid:
 * every published version plus the current draft, each tagged with a State
 * (active / published / draft). Row actions are contextual — Publish on the draft,
 * Activate / Delete on inactive published versions. The active version (the one
 * whose number matches the mapping's {@code versionNumber}) has no actions.
 *
 * Notes are edited inline via the Cumulocity "edit on focus" pattern; no modal is shown.
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
  readonly rows$ = new BehaviorSubject<VersionRow[]>([]);
  readonly pagination: Pagination = { pageSize: 100, currentPage: 1 };
  loading = true;
  busy = false;

  /** Tracks the note entered in the draft row; used when publishing. */
  draftNote = '';

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
          note: v.note || '',
          updatedDisplay: v.createdAt ? new Date(v.createdAt).toLocaleString() : '—',
          createdBy: v.createdBy || '—',
          isDraft: false,
          onNoteChange: this.canManage
            ? (note: string) => this.saveVersionNote(v.id ?? `v${v.versionNumber}`, v.versionNumber, note)
            : undefined
        }));

      this.draftNote = draft?.versionNote ?? '';

      const draftRow: VersionRow[] = draft
        ? [{
          id: DRAFT_ROW_ID,
          versionNumber: 0,
          versionDisplay: '—',
          state: 'draft',
          note: this.draftNote,
          updatedDisplay: draft.lastUpdate ? new Date(draft.lastUpdate).toLocaleString() : '—',
          createdBy: '—',
          isDraft: true,
          onNoteChange: this.canManage
            ? (note: string) => { this.draftNote = note; }
            : undefined
        }]
        : [];

      this.rows$.next([...draftRow, ...versionRows]);
    } catch (e) {
      this.alertService.danger('Failed to load versions', (e as Error).message);
    } finally {
      this.loading = false;
    }
  }

  private async saveVersionNote(versionId: string, versionNumber: number, note: string): Promise<void> {
    try {
      await this.mappingService.updateVersionNote(this.mapping.id, versionNumber, note);
      this.changed = true;
      // Update the row value in-place so the grid reflects the saved state.
      const rows = this.rows$.getValue();
      const row = rows.find(r => r.id === versionId);
      if (row) {
        row.note = note || '';
        this.rows$.next([...rows]);
      }
    } catch (e) {
      this.alertService.danger('Failed to update note', (e as Error).message);
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
    this.busy = true;
    try {
      const version = await this.mappingService.publishDraft(this.mapping.id, this.draftNote || undefined);
      this.alertService.success(`Published version ${version.versionNumber} of ${this.mapping.name}`);
      this.draftNote = '';
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to publish draft', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  close(): void {
    this.closeSubject.next(this.changed);
    this.closeSubject.complete();
    this.bottomDrawerRef.close();
  }

  private buildColumns(): Column[] {
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
        name: 'note',
        header: 'Note',
        path: 'note',
        gridTrackSize: '40%',
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: NoteEditCellRendererComponent
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
