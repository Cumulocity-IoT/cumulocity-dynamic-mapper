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

import { Component, ElementRef, HostListener, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { MappingService } from '../mapping/core/mapping.service';
import { Direction, Feature, NODE1, NODE3 } from '../shared';
import { BehaviorSubject, from, Subject } from 'rxjs';
import { ConnectorConfigurationService } from '../connector';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { DocMarkdownService } from './doc-markdown.service';

@Component({
  selector: 'd11r-doc-overview',
  templateUrl: './doc-overview.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [CoreModule, CommonModule, RouterLink],
  // Most of the body is rendered markdown injected via [innerHTML], which never receives the
  // _ngcontent-* attribute Angular's default (emulated) encapsulation scopes styles with — so
  // styleUrls would silently never apply to it (see DocPageComponent for the same fix).
  encapsulation: ViewEncapsulation.None
})
export class DocOverviewComponent implements OnInit {
  @ViewChild('docBody', { static: false }) docBodyRef: ElementRef<HTMLElement>;

  countMappingInbound$: Subject<any> = new BehaviorSubject<any>(0);
  countMappingOutbound$: Subject<any> = new BehaviorSubject<any>(0);
  countConnector$: Subject<any> = new BehaviorSubject<any>(0);
  feature: Feature;

  // The overview page is now short: the intro prose, the live tenant counts, then the
  // "where to start" tier table. Everything that used to be concatenated onto this page
  // (getting started, connectors, mappings, SparkPlug B, monitoring, …) is its own route.
  htmlIntro: SafeHtml = '';
  htmlNextSteps: SafeHtml = '';

  ROUTE_INBOUND: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`;
  ROUTE_OUTBOUND: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`;
  ROUTE_CONNECTORS: string = `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`;

  // Internal app routes (e.g. /c8y-pkg-dynamic-mapper/introduction/smartfunction) are
  // intercepted below so navigation goes through the Angular router instead of a full
  // page reload; external links (target="_blank") pass through untouched.
  private static readonly INTERNAL_LINK_PREFIX = '/c8y-pkg-dynamic-mapper/';

  constructor(
    private mappingService: MappingService,
    private alertService: AlertService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private route: ActivatedRoute,
    private router: Router,
    private markdownService: DocMarkdownService,
    private sanitizer: DomSanitizer
  ) {}

  async ngOnInit(): Promise<void> {
    this.feature = this.route.snapshot.data['feature'];

    const [intro, nextSteps] = await Promise.all([
      this.markdownService.loadAndRender('overview'),
      this.markdownService.loadAndRender('overview-next-steps')
    ]);
    this.htmlIntro = this.sanitizer.bypassSecurityTrustHtml(intro.html);
    this.htmlNextSteps = this.sanitizer.bypassSecurityTrustHtml(nextSteps.html);
    // setTimeout (a macrotask) runs after Angular's zone-triggered change detection has
    // committed the three [innerHTML] bindings above to the DOM, so docBodyRef.nativeElement
    // actually contains the <pre class="mermaid"> nodes mermaid.run() needs to find.
    setTimeout(() => this.markdownService.renderMermaidDiagrams(this.docBodyRef.nativeElement));

    from(this.mappingService.getMappings(Direction.INBOUND)).subscribe(
      (mappings) => { this.countMappingInbound$.next(!mappings ? 'no' : mappings.length); }
    );

    from(this.mappingService.getMappings(Direction.OUTBOUND)).subscribe(
      (count) => this.countMappingOutbound$.next(!count ? 'no' : count.length)
    );

    this.connectorConfigurationService.getConfigurations()
      .subscribe((count) => this.countConnector$.next(!count ? 'no' : count.length));

    if (!this.feature?.userHasMappingAdminRole && !this.feature?.userHasMappingCreateRole) {
      this.alertService.warning(
        "You don't have any Dynamic Mapper permissions and therefore can only view mappings/connectors. Please contact your administrator."
      );
    } else if (!this.feature?.userHasMappingAdminRole) {
      this.alertService.warning(
        "You don't have the role 'Dynamic Mapper Admin' and therefore cannot create or edit connectors. Please contact your administrator."
      );
    } else if (!this.feature?.userHasMappingCreateRole) {
      this.alertService.warning(
        "You don't have the role 'Dynamic Mapper User' and therefore cannot edit mappings. Please contact your administrator."
      );
    }
  }

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      window.scrollTo({ top: element.offsetTop - 120, behavior: 'smooth' });
    }
  }

  @HostListener('click', ['$event'])
  onClick(event: MouseEvent): void {
    const target = (event.target as HTMLElement)?.closest('a');
    if (!target) return;
    const href = target.getAttribute('href');
    if (!href) return;
    if (href.startsWith('#')) {
      event.preventDefault();
      this.scrollToElement(href.slice(1));
      return;
    }
    if (!href.startsWith(DocOverviewComponent.INTERNAL_LINK_PREFIX)) return;
    event.preventDefault();
    this.router.navigateByUrl(href);
  }
}
