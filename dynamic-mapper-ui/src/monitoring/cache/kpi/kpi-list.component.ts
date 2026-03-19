import { Component, Input } from '@angular/core';
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
            'inboundIdCache': 'Inbound ID Cache'
        };
        return displayNames[domain] || domain;
    }
}
