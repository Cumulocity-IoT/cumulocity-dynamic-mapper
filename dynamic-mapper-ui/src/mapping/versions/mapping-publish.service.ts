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

import { Injectable, inject } from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { take } from 'rxjs/operators';

import { Mapping } from '../../shared';
import { ConfirmationModalService } from '../../shared/service/confirmation-modal.service';
import { MappingService } from '../core/mapping.service';
import { PublishVersionModalComponent } from './publish-version-modal.component';

/** What a publish attempt actually did, so callers know whether to refresh. */
export interface PublishOutcome {
  /** false when the user cancelled the version dialog, or publishing failed. */
  published: boolean;
  /** true when the mapping was also activated as part of this flow. */
  activated: boolean;
}

/**
 * Publishing a draft, shared by the version drawer and the mapping grid's "Publish draft" row
 * action so the two cannot drift apart.
 *
 * It also closes the second half of the gap: publishing a draft does not make a mapping live if
 * the mapping is inactive. Rather than leaving the user to notice the Activate toggle afterwards,
 * the flow offers to activate straight away.
 */
@Injectable({ providedIn: 'root' })
export class MappingPublishService {
  private readonly mappingService = inject(MappingService);
  private readonly bsModalService = inject(BsModalService);
  private readonly alertService = inject(AlertService);
  private readonly confirmationService = inject(ConfirmationModalService);

  async publishDraft(mapping: Mapping): Promise<PublishOutcome> {
    const version = await this.askForVersion(mapping);
    if (!version) return { published: false, activated: false };

    let publishedVersion: string;
    try {
      const mv = await this.mappingService.publishDraft(mapping.id, version.version, version.note || undefined);
      publishedVersion = mv.version;
    } catch (e) {
      this.alertService.danger('Failed to publish draft', (e as Error).message);
      return { published: false, activated: false };
    }

    const activated = await this.offerActivation(mapping, publishedVersion);
    if (!activated) {
      this.alertService.success(`Published version ${publishedVersion} of ${mapping.name}`);
    }
    return { published: true, activated };
  }

  /** Version suggestions come from the backend; fall back so the dialog still opens if that fails. */
  private async askForVersion(mapping: Mapping): Promise<{ version: string; note: string } | null> {
    let suggestions: { patch: string; minor: string; major: string };
    try {
      suggestions = await this.mappingService.suggestNextVersions(mapping.id);
    } catch {
      suggestions = { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' };
    }

    return new Promise<{ version: string; note: string } | null>(resolve => {
      const ref = this.bsModalService.show(PublishVersionModalComponent, {
        initialState: {
          mappingName: mapping.name,
          currentVersion: mapping.version ?? null,
          suggestions
        }
      });
      ref.content.closeSubject.pipe(take(1)).subscribe((r: { version: string; note: string } | null) => {
        resolve(r);
        ref.hide();
      });
    });
  }

  /**
   * An already-active mapping picks the new version up on publish, so there is nothing to ask.
   * An inactive one stays dormant, which is the step users miss — so offer it explicitly.
   */
  private async offerActivation(mapping: Mapping, publishedVersion: string): Promise<boolean> {
    if (mapping.active) return false;

    const confirmed = await this.confirmationService.confirm({
      title: 'Activate mapping?',
      message: `Version ${publishedVersion} of "${mapping.name}" has been published, but the mapping is `
        + `inactive, so it will not process any messages yet. Activate it now?`,
      labels: { ok: 'Activate now', cancel: 'Leave inactive' }
    });
    if (!confirmed) {
      this.alertService.success(
        `Published version ${publishedVersion} of ${mapping.name}. It stays inactive until you activate it.`);
      return false;
    }

    try {
      await this.mappingService.activateVersion(mapping.id, publishedVersion);
      mapping.active = true;
      this.alertService.success(`Published and activated version ${publishedVersion} of ${mapping.name}`);
      return true;
    } catch (e) {
      this.alertService.danger('Published, but activating the mapping failed', (e as Error).message);
      return false;
    }
  }
}
