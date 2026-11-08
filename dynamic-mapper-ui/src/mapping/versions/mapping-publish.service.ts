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
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { take } from 'rxjs/operators';

import { Direction, Mapping } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';
import { PublishVersionModalComponent } from './publish-version-modal.component';

/** What a publish attempt actually did, so callers know whether to refresh. */
export interface PublishOutcome {
  /** false when the user cancelled the version dialog, or publishing failed. */
  published: boolean;
  /** true when the user closed the version dialog without publishing (nothing went wrong). */
  cancelled?: boolean;
  /** true when the mapping was also activated as part of this flow. */
  activated: boolean;
}

/**
 * Publishing a draft, shared by the version drawer and the mapping grid's "Publish draft" row
 * action so the two cannot drift apart.
 *
 * It also closes the second half of the gap: publishing a draft does not make a mapping live.
 * The version dialog carries an activation option, so publishing and activating are one dialog
 * instead of a second confirmation.
 */
@Injectable({ providedIn: 'root' })
export class MappingPublishService {
  private readonly mappingService = inject(MappingService);
  private readonly bsModalService = inject(BsModalService);
  private readonly alertService = inject(AlertService);
  private readonly translateService = inject(TranslateService);
  private readonly subscriptionService = inject(SubscriptionService);

  async publishDraft(mapping: Mapping): Promise<PublishOutcome> {
    const version = await this.askForVersion(mapping);
    if (!version) return { published: false, activated: false, cancelled: true };

    let publishedVersion: string;
    try {
      const mv = await this.mappingService.publishDraft(mapping.id, version.version, version.note || undefined);
      publishedVersion = mv.version;
    } catch (e) {
      this.alertService.danger(gettext('Failed to publish draft'), (e as Error).message);
      return { published: false, activated: false };
    }

    if (!version.activate) {
      this.alertService.success(mapping.active
        ? this.translateService.instant(
          gettext('Published version {{version}} of {{name}}. It keeps running {{current}} until you activate the new version.'),
          { version: publishedVersion, name: mapping.name, current: mapping.version })
        : this.translateService.instant(
          gettext('Published version {{version}} of {{name}}. It stays inactive until you activate it.'),
          { version: publishedVersion, name: mapping.name }));
      return { published: true, activated: false };
    }

    const activated = await this.activate(mapping, publishedVersion);
    return { published: true, activated };
  }

  /** Version suggestions come from the backend; fall back so the dialog still opens if that fails. */
  private async askForVersion(mapping: Mapping): Promise<{ version: string; note: string; activate: boolean } | null> {
    let suggestions: { patch: string; minor: string; major: string };
    try {
      suggestions = await this.mappingService.suggestNextVersions(mapping.id);
    } catch {
      suggestions = { patch: '1.0.0', minor: '1.0.0', major: '1.0.0' };
    }

    return new Promise<{ version: string; note: string; activate: boolean } | null>(resolve => {
      const ref = this.bsModalService.show(PublishVersionModalComponent, {
        initialState: {
          mappingName: mapping.name,
          currentVersion: mapping.version ?? null,
          mappingActive: !!mapping.active,
          suggestions
        }
      });
      ref.content.closeSubject.pipe(take(1)).subscribe((r: { version: string; note: string; activate: boolean } | null) => {
        resolve(r);
        ref.hide();
      });
    });
  }

  /**
   * Activates `version` of `mapping`. Public because Save & Activate has a connector-only path that
   * activates without publishing.
   *
   * An outbound mapping with no device subscribed would be active but receive nothing. The
   * activation toggle in the grid refuses that case, so this does too instead of being a way around it.
   */
  async activate(mapping: Mapping, version: string): Promise<boolean> {
    if (mapping.direction === Direction.OUTBOUND) {
      const subscribed = await this.subscriptionService.validateSubscriptionOutbound(Direction.OUTBOUND);
      if (!subscribed) {
        this.alertService.warning(this.translateService.instant(
          gettext('Version {{version}} of {{name}} is saved, but the mapping was not activated.'),
          { version, name: mapping.name }
        ));
        return false;
      }
    }
    try {
      await this.mappingService.activateVersion(mapping.id, version);
      mapping.active = true;
      mapping.version = version;
      this.alertService.success(this.translateService.instant(
        gettext('Published and activated version {{version}} of {{name}}'),
        { version, name: mapping.name }
      ));
      return true;
    } catch (e) {
      this.alertService.danger(gettext('Saved, but activating the version failed'), (e as Error).message);
      return false;
    }
  }
}
