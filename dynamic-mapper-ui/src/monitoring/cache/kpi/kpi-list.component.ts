/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { Component, EventEmitter, Input, Output, ChangeDetectionStrategy } from '@angular/core';
import {
    BreadcrumbModule,
    C8yTranslateModule,
    HeaderModule,
    HelpModule,
    IconDirective,

} from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { KpiItemComponent } from './kpi-item.component';
import { KpiDetails } from '../../shared/monitoring.service';

@Component({
    selector: 'd11r-kpi-list',
    templateUrl: './kpi-list.component.html',
    styleUrls: ['../../../shared/mapping/mapping.style.css'],
    imports: [
        CommonModule,
        HeaderModule,
        HelpModule,
        C8yTranslateModule,
        KpiItemComponent,
        RouterLink, IconDirective,
        BreadcrumbModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    standalone: true
})
export class KpiListComponent {
    @Input() kpis: KpiDetails[];
    /** Whether the user may clear caches; disables the per-row clear buttons. */
    @Input() canClear = false;
    /** Domains (e.g. `inboundIdCache`) that offer a clear action. */
    @Input() clearableDomains: string[] = [];
    /** Emits the domain of the row whose clear button was clicked. */
    @Output() clear = new EventEmitter<string>();

    isClearable(domain: string): boolean {
        return this.clearableDomains.includes(domain);
    }

    get kpisByDomain(): Map<string, KpiDetails[]> {
        if (!this.kpis) {
            return new Map();
        }

        return this.kpis.reduce((map, kpi) => {
            const domain = kpi.domain;
            if (!map.has(domain)) {
                map.set(domain, []);
            }
            map.get(domain)!.push(kpi);
            return map;
        }, new Map<string, KpiDetails[]>());
    }

    get domains(): string[] {
        return Array.from(this.kpisByDomain.keys());
    }

    getDomainDisplayName(domain: string): string {
        const displayNames: { [key: string]: string } = {
            'inventoryCache': 'Inventory Cache',
            'inboundIdCache': 'Inbound ID Cache',
            'outboundIdCache': 'Outbound ID Cache'
        };
        return displayNames[domain] || domain;
    }
}
