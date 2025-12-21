/* eslint-disable @angular-eslint/no-empty-lifecycle-method */
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

import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { MappingService } from '../mapping/core/mapping.service';
import { CodeExplorerComponent, Direction, Feature, JsonEditorComponent, NODE1, NODE3 } from '../shared';
import { BehaviorSubject, from, Subject, Subscription } from 'rxjs';
import { ConnectorConfigurationService } from '../connector';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { BsModalService } from 'ngx-bootstrap/modal';
import { SharedService } from '../shared/service/shared.service';
import { CodeTemplate, CodeTemplateMap } from '../configuration/shared/configuration.model';
import { base64ToString } from '../mapping/shared/util';

@Component({
  selector: 'd11r-landing',
  templateUrl: './doc-main.component.html',
  styleUrl: './doc-shared.css',
  standalone: true,
  imports: [
    CoreModule,
    CommonModule,
    RouterLink
  ]
})
export class DocMainComponent implements OnInit, OnDestroy {
  constructor(
    private mappingService: MappingService,
    private alertService: AlertService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private sanitizer: DomSanitizer,
    private route: ActivatedRoute,
    private bsModalService: BsModalService,
    private sharedService: SharedService
  ) {
    this.linkSVG = this.sanitizer.bypassSecurityTrustUrl(
      'image/Dynamic_Mapper_Snooping_Stepper_Process.svg'
    );
  }
  @ViewChild('editorTest', { static: false }) editorTest: JsonEditorComponent;

  codeTemplates: CodeTemplate[] = [];

  ROUTE_INBOUND: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`;
  ROUTE_ADD_CONNECTOR: string = `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`;
  ROUTE_SERVICE_CONFIGURATION: string = `/c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration`;
  ROUTE_OUTBOUND: string =
    `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`;
  ROUTE_CONNECTORS: string = `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`;
  ROUTE_CODE_TEMPLATES_INBOUND_SMART_FUNCTION= `/c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/INBOUND_SMART_FUNCTION`;
  countMappingInbound$: Subject<any> = new BehaviorSubject<any>(0);
  countMappingOutbound$: Subject<any> = new BehaviorSubject<any>(0);
  countConnector$: Subject<any> = new BehaviorSubject<any>(0);
  linkSVG: SafeResourceUrl;

  feature: Feature;

  currentPage: string = 'main';
  private fragmentSubscription: Subscription;

  async ngOnInit() {

    this.feature = this.route.snapshot.data['feature'];

    // Determine which page to display based on the URL path
    const path = this.route.snapshot.routeConfig?.path || '';

    // Map of path segments to their corresponding element IDs for scrolling
    const pathToFragmentMap: { [key: string]: string } = {
      'overview': 'overview',
      'getting-started': 'getting-started',
      'managing-connectors': 'managing-connectors',
      'define-mapping': 'define-mapping',
      'define-subscription-for-outbound': 'define-subscription-for-outbound',
      'transformation-types': 'transformation-types',
      'code-templates': 'code-templates',
      'metadata': 'metadata',
      'unknown-payload': 'unknown-payload',
      'reliability-settings': 'reliability-settings',
      'access-control': 'access-control'
    };

    if (path.includes('jsonata')) {
      this.currentPage = 'jsonata';
    } else if (path.includes('javascript')) {
      this.currentPage = 'javascript';
    } else if (path.includes('smartfunction')) {
      this.currentPage = 'smartfunction';
    } else {
      this.currentPage = 'main';
    }

    // Subscribe to fragment changes for navigation anchor scrolling (fallback for hash-based navigation)
    this.fragmentSubscription = this.route.fragment.subscribe(fragment => {
      if (fragment) {
        // Wait for DOM to render before scrolling
        setTimeout(() => {
          this.scrollToElement(fragment);
        }, 100);
      }
    });

    // Only load data for the main page
    if (this.currentPage === 'main') {
      // Load code templates
      const codeTemplatesMap: CodeTemplateMap = await this.sharedService.getCodeTemplates();
      this.codeTemplates = Object.entries(codeTemplatesMap)
        .map(([, template]) => template)
        .sort((a, b) => {
          // Sort by template type first, then by name
          const typeOrder = {
            'INBOUND_SUBSTITUTION_AS_CODE': 1,
            'OUTBOUND_SUBSTITUTION_AS_CODE': 2,
            'INBOUND_SMART_FUNCTION': 3,
            'OUTBOUND_SMART_FUNCTION': 4,
            'SHARED': 5,
            'SYSTEM': 6
          };
          const typeComparison = (typeOrder[a.templateType] || 999) - (typeOrder[b.templateType] || 999);
          if (typeComparison !== 0) return typeComparison;
          return a.name.localeCompare(b.name);
        });

      from(this.mappingService.getMappings(Direction.INBOUND)).subscribe(
        (mappings) => {
          this.countMappingInbound$.next(!mappings ? 'no' : mappings.length);
        }
      );

      from(this.mappingService.getMappings(Direction.OUTBOUND)).subscribe(
        (count) => this.countMappingOutbound$.next(!count ? 'no' : count.length)
      );

      this.connectorConfigurationService.getConfigurations()
        .subscribe((count) =>
          this.countConnector$.next(!count ? 'no' : count.length)
        );

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

      // After data is loaded, check if we need to scroll to a specific section
      const pathSegment = path.split('/').pop() || '';
      const fragmentId = pathToFragmentMap[pathSegment];

      if (fragmentId) {
        // Wait for DOM to fully render with loaded data before scrolling
        setTimeout(() => {
          this.scrollToElement(fragmentId);
        }, 200);
      } else {
        // No specific section, scroll to top
        setTimeout(() => {
          window.scrollTo(0, 0);
        }, 0);
      }
    }
  }

  openCodeExplorer(template: CodeTemplate): void {
    this.bsModalService.show(CodeExplorerComponent, {
      initialState: {
        templateCode: base64ToString(template.code),
        templateName: template.name
      },
      class: 'modal-lg'
    });
  }

  getTransformationTypeName(templateType: string): string {
    switch (templateType) {
      case 'INBOUND_SUBSTITUTION_AS_CODE':
      case 'OUTBOUND_SUBSTITUTION_AS_CODE':
        return 'JavaScript Substitutions';
      case 'INBOUND_SMART_FUNCTION':
      case 'OUTBOUND_SMART_FUNCTION':
        return 'Smart Functions';
      case 'SHARED':
        return 'Shared Code';
      case 'SYSTEM':
        return 'System Code';
      case 'INBOUND':
        return 'Inbound (deprecated)';
      case 'OUTBOUND':
        return 'Outbound (deprecated)';
      case 'SUBSTITUTION_AS_CODE':
        return 'Substitution as Code';
      default:
        return templateType;
    }
  }

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      const elementPosition = element.getBoundingClientRect().top + window.scrollY;
      const offsetPosition = elementPosition - 80; // Offset to show heading with some space above

      window.scrollTo({
        top: offsetPosition,
        behavior: 'smooth'
      });
    }
  }

  ngOnDestroy(): void {
    if (this.fragmentSubscription) {
      this.fragmentSubscription.unsubscribe();
    }
  }

}