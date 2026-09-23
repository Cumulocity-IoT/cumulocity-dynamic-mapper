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
import { MappingPublishService } from './mapping-publish.service';

type VersionState = 'active' | 'published' | 'draft';

/** One row of the versions grid: a published version, or the single draft. */
interface VersionRow {
  id: string;
  version: string;
  versionDisplay: string;
  state: VersionState;
  note: string;
  updatedDisplay: string;
  createdBy: string;
  isDraft: boolean;
  /** Injected per-row by the drawer. Absent when canManage=false (makes the cell read-only). */
  onNoteChange?: (note: string) => void;
  /**
   * Injected per-row by the drawer, only for published (non-active, non-draft) rows when
   * canManage=true. Absent for the active row itself (nothing to activate) and for the draft
   * (must be published first) — VersionStateCellRendererComponent's toggle is disabled
   * whenever this is absent.
   */
  onActivate?: () => void;
}

const DRAFT_ROW_ID = '__draft__';

/** Parses a semver string "X.Y.Z" into a numeric tuple for sorting. Falls back to [0,0,0]. */
function parseSemVer(v: string | null | undefined): [number, number, number] {
  if (!v) return [0, 0, 0];
  const m = v.match(/^(\d+)\.(\d+)\.(\d+)$/);
  return m ? [+m[1], +m[2], +m[3]] : [0, 0, 0];
}

function compareSemVerDesc(a: string | null | undefined, b: string | null | undefined): number {
  const [aMaj, aMin, aPat] = parseSemVer(a);
  const [bMaj, bMin, bPat] = parseSemVer(b);
  return bMaj - aMaj || bMin - aMin || bPat - aPat;
}

/**
 * Bottom drawer listing all records of a mapping line in a single c8y-data-grid:
 * every published version plus the current draft, each tagged with a State
 * (active / published / draft).
 *
 * The State column doubles as the activation control (VersionStateCellRendererComponent) —
 * fixed 2026-09-23: a separate "Activate" row action used a toggle-on *icon* next to a
 * published row while the active row's State badge sat in its own column with no toggle at
 * all, which read as two disagreeing indicators (a switch that looks "on" for a row that isn't
 * actually active). Now there is one indicator per row: the active version's toggle is on and
 * disabled, every published version's toggle is off and clickable to activate it (which
 * implicitly deactivates whichever was active before), and the draft — not directly
 * activatable, it must be published first — keeps its plain badge with no toggle. Remaining row
 * actions are Publish/Discard on the draft and Delete on inactive published versions.
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
  private readonly publishService = inject(MappingPublishService);

  /** Emits true if anything changed, so the opener can refresh the mapping grid. */
  closeSubject = new Subject<boolean>();

  columns: Column[] = this.buildColumns();
  actionControls: ActionControl[] = this.buildActionControls();
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
      const [versions, draft, freshMapping] = await Promise.all([
        this.mappingService.getVersions(this.mapping.id),
        this.mappingService.getDraft(this.mapping.id),
        this.mappingService.getMapping(this.mapping.id)
      ]);
      // Always use the server's authoritative active version — the input mapping can be stale.
      if (freshMapping?.version != null) {
        this.mapping.version = freshMapping.version;
      }

      const versionRows: VersionRow[] = (versions ?? [])
        .sort((a, b) => compareSemVerDesc(a.version, b.version))
        .map(v => {
          const rowId = v.id ?? `v${v.version}`;
          const state = (v.version === this.mapping.version ? 'active' : 'published') as VersionState;
          const row: VersionRow = {
            id: rowId,
            version: v.version ?? '',
            versionDisplay: v.version ? `v${v.version}` : '—',
            state,
            note: v.note || '',
            updatedDisplay: v.createdAt ? new Date(v.createdAt).toLocaleString() : '—',
            createdBy: v.createdBy || '—',
            isDraft: false,
            onNoteChange: this.canManage
              ? (note: string) => this.saveVersionNote(rowId, v.version ?? '', note)
              : undefined
          };
          // Set after `row` exists so the closure can pass the row itself to activate().
          row.onActivate = this.canManage && state !== 'active' ? () => this.activate(row) : undefined;
          return row;
        });

      const draftNote = draft?.versionNote ?? '';

      const draftRow: VersionRow[] = draft
        ? [{
          id: DRAFT_ROW_ID,
          version: '',
          versionDisplay: '—',
          state: 'draft',
          note: draftNote,
          updatedDisplay: draft.lastUpdate ? new Date(draft.lastUpdate).toLocaleString() : '—',
          createdBy: '—',
          isDraft: true,
          onNoteChange: undefined
        }]
        : [];

      this.rows$.next([...draftRow, ...versionRows]);
    } catch (e) {
      this.alertService.danger('Failed to load versions', (e as Error).message);
    } finally {
      this.loading = false;
    }
  }

  private async saveVersionNote(versionId: string, version: string, note: string): Promise<void> {
    try {
      await this.mappingService.updateVersionNote(this.mapping.id, version, note);
      this.changed = true;
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
    if (this.busy) {
      // The activation toggle in VersionStateCellRendererComponent is only disabled by
      // rebuilding rows (onActivate absent) after this method sets busy=true and reload()
      // completes — there's a window between those two points where a second click could still
      // reach here. Guarded here too since that race isn't otherwise prevented at the UI level.
      return;
    }
    this.busy = true;
    try {
      await this.mappingService.activateVersion(this.mapping.id, row.version);
      this.alertService.success(`Activated version ${row.version} of ${this.mapping.name}`);
      this.mapping.version = row.version;
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
      await this.mappingService.deleteVersion(this.mapping.id, row.version);
      this.alertService.success(`Deleted version ${row.version} of ${this.mapping.name}`);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to delete version', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  /** Confirms deletion of a version or draft; resolves true if the user proceeds. */
  private confirmDelete(row: Pick<VersionRow, 'versionDisplay'>): Promise<boolean> {
    const isDraft = row.versionDisplay === 'draft';
    return new Promise(resolve => {
      const ref = this.bsModalService.show(ConfirmationModalComponent, {
        initialState: {
          title: isDraft ? 'Discard draft' : `Delete version ${row.versionDisplay}`,
          message: isDraft
            ? `You are about to permanently discard the draft of ${this.mapping.name}. All unsaved changes will be lost. Do you want to proceed?`
            : `You are about to permanently delete ${row.versionDisplay} of ${this.mapping.name}. This cannot be undone. Do you want to proceed?`,
          labels: { ok: isDraft ? 'Discard' : 'Delete', cancel: 'Cancel' }
        }
      });
      ref.content.closeSubject.pipe(take(1)).subscribe((result: boolean) => {
        resolve(!!result);
        ref.hide();
      });
    });
  }

  async removeDraft(): Promise<void> {
    const confirmed = await this.confirmDelete({ versionDisplay: 'draft' } as VersionRow);
    if (!confirmed) return;
    this.busy = true;
    try {
      await this.mappingService.deleteDraft(this.mapping.id);
      this.alertService.success(`Discarded draft of ${this.mapping.name}`);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to discard draft', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  async publish(): Promise<void> {
    this.busy = true;
    try {
      // Shared with the grid's "Publish draft" row action; also offers activation when the
      // mapping is still inactive. See MappingPublishService.
      const outcome = await this.publishService.publishDraft(this.mapping);
      if (outcome.published) {
        this.changed = true;
        await this.reload();
      }
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
        name: 'versionDisplay',
        header: 'Version',
        path: 'versionDisplay',
        gridTrackSize: '7%',
        // No sortOrder: the default (newest first, draft pinned to the top) comes from
        // compareSemVerDesc() sorting the rows in reload() instead — same reasoning as
        // MappingVersionsCountComponent's 'name' column.
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'note',
        header: 'Note',
        path: 'note',
        gridTrackSize: '40%',
        sortable: true,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: NoteEditCellRendererComponent
      },
      {
        name: 'updatedDisplay',
        header: 'Updated',
        path: 'updatedDisplay',
        gridTrackSize: '17.5%',
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
      {
        name: 'createdBy',
        header: 'By',
        path: 'createdBy',
        gridTrackSize: '21.5%',
        sortable: true,
        dataType: ColumnDataType.TextShort
      },
      {
        // Second-to-last column, right before the grid's own actions column (Delete) — the
        // activation toggle reads as the last real decision a user makes about a row before
        // any destructive action, not as identifying metadata to scan first.
        name: 'state',
        header: 'State',
        path: 'state',
        sortable: true,
        dataType: ColumnDataType.TextShort,
        gridTrackSize: '7%',
        cellRendererComponent: VersionStateCellRendererComponent
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
        type: 'DISCARD_DRAFT',
        text: 'Discard',
        icon: 'trash-o',
        callback: () => this.removeDraft(),
        showIf: (row: VersionRow) => this.canManage && row.isDraft && !this.busy
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
