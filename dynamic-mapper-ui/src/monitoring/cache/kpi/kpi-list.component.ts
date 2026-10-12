import { Component, EventEmitter, Input, Output } from '@angular/core';
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
    standalone: true
})
export class KpListComponent {
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
