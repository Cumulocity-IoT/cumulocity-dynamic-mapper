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
import { Component, Input, OnInit } from '@angular/core';
import { AlertService, CoreModule, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Mapping, MappingVersion, SharedModule } from '../../shared';
import { MappingService } from '../core/mapping.service';

/**
 * Lists the versions of a mapping line and lets the user activate (roll back /
 * forward), delete inactive versions, and publish the current draft. The active
 * version is the one whose number matches the mapping's `versionNumber`.
 */
@Component({
  selector: 'd11r-mapping-version-modal',
  templateUrl: './mapping-version-modal.component.html',
  standalone: true,
  imports: [CoreModule, SharedModule]
})
export class MappingVersionModalComponent implements OnInit {
  @Input() mapping: Mapping;
  /** Whether the current user may change versions (activate/publish/delete). */
  @Input() canManage = true;

  closeSubject: Subject<boolean> = new Subject<boolean>();
  labels: ModalLabels = { cancel: 'Close' };

  versions: MappingVersion[] = [];
  draft: Mapping | null = null;
  loading = true;
  busy = false;
  private changed = false;

  constructor(
    private readonly mappingService: MappingService,
    private readonly alertService: AlertService
  ) {}

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.loading = true;
    try {
      const [versions, draft] = await Promise.all([
        this.mappingService.getVersions(this.mapping.id),
        this.mappingService.getDraft(this.mapping.id)
      ]);
      this.versions = (versions ?? []).sort((a, b) => b.versionNumber - a.versionNumber);
      this.draft = draft;
    } catch (e) {
      this.alertService.danger('Failed to load versions', (e as Error).message);
    } finally {
      this.loading = false;
    }
  }

  isActive(v: MappingVersion): boolean {
    return v.versionNumber === this.mapping.versionNumber;
  }

  async activate(v: MappingVersion): Promise<void> {
    this.busy = true;
    try {
      await this.mappingService.activateVersion(this.mapping.id, v.versionNumber);
      this.alertService.success(`Activated version ${v.versionNumber} of ${this.mapping.name}`);
      this.mapping.versionNumber = v.versionNumber;
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to activate version', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  async remove(v: MappingVersion): Promise<void> {
    this.busy = true;
    try {
      await this.mappingService.deleteVersion(this.mapping.id, v.versionNumber);
      this.alertService.success(`Deleted version ${v.versionNumber}`);
      this.changed = true;
      await this.reload();
    } catch (e) {
      this.alertService.danger('Failed to delete version', (e as Error).message);
    } finally {
      this.busy = false;
    }
  }

  async publish(): Promise<void> {
    this.busy = true;
    try {
      const version = await this.mappingService.publishDraft(this.mapping.id);
      this.alertService.success(`Published version ${version.versionNumber} of ${this.mapping.name}`);
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
  }
}
