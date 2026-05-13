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

import { Component, OnInit } from '@angular/core';
import { MappingService } from '../mapping/core/mapping.service';
import { Direction, Feature, NODE1, NODE2, NODE3 } from '../shared';
import { BehaviorSubject, from, Subject } from 'rxjs';
import { ConnectorConfigurationService } from '../connector';
import { AlertService, BottomDrawerService, CoreModule } from '@c8y/ngx-components';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { SharedService } from '../shared/service/shared.service';
import { CodeTemplate, CodeTemplateMap } from '../configuration/shared/configuration.model';
import { CodeEditorDrawerComponent } from '../shared/component/code-explorer/code-editor-drawer.component';

@Component({
  selector: 'd11r-doc-overview',
  templateUrl: './doc-overview.component.html',
  styleUrls: ['./doc-shared.css'],
  standalone: true,
  imports: [CoreModule, CommonModule, RouterLink]
})
export class DocOverviewComponent implements OnInit {
  codeTemplates: CodeTemplate[] = [];
  countMappingInbound$: Subject<any> = new BehaviorSubject<any>(0);
  countMappingOutbound$: Subject<any> = new BehaviorSubject<any>(0);
  countConnector$: Subject<any> = new BehaviorSubject<any>(0);
  feature: Feature;

  ROUTE_INBOUND: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`;
  ROUTE_ADD_CONNECTOR: string = `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`;
  ROUTE_SERVICE_CONFIGURATION: string = `/c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration/general`;
  ROUTE_OUTBOUND: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`;
  ROUTE_CONNECTORS: string = `/c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`;
  ROUTE_CODE_TEMPLATES_INBOUND_SMART_FUNCTION: string = `/c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/INBOUND_SMART_FUNCTION`;
  ROUTE_MONITORING: string = `/c8y-pkg-dynamic-mapper/${NODE2}/monitoring/statistic/inbound`;
  ROUTE_MESSAGE_EXPLORER: string = `/c8y-pkg-dynamic-mapper/${NODE1}/mappings/messageExplorer`;

  constructor(
    private mappingService: MappingService,
    private alertService: AlertService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private route: ActivatedRoute,
    private bottomDrawerService: BottomDrawerService,
    private sharedService: SharedService
  ) {}

  async ngOnInit(): Promise<void> {
    this.feature = this.route.snapshot.data['feature'];

    // When navigating to a section anchor within the overview page, scroll to it.
    // Use offsetTop (layout-based, scroll-independent) rather than
    // getBoundingClientRect() so the correct offset is computed regardless of the
    // window scroll position at the time this runs.
    const path = this.route.snapshot.routeConfig?.path || '';
    if (path && path !== '') {
      setTimeout(() => {
        const element = document.getElementById(path);
        if (element) {
          window.scrollTo({ top: element.offsetTop - 120, behavior: 'smooth' });
        }
      }, 200);
    }

    const codeTemplatesMap: CodeTemplateMap = await this.sharedService.getCodeTemplates();
    this.codeTemplates = Object.entries(codeTemplatesMap)
      .map(([, template]) => template)
      .sort((a, b) => {
        const typeOrder = {
          'INBOUND_SMART_FUNCTION': 1,
          'OUTBOUND_SMART_FUNCTION': 2,
          'SHARED': 3,
          'SYSTEM': 4
        };
        const typeComparison = (typeOrder[a.templateType] || 999) - (typeOrder[b.templateType] || 999);
        if (typeComparison !== 0) return typeComparison;
        return a.name.localeCompare(b.name);
      });

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

  openCodeExplorer(template: CodeTemplate): void {
    this.bottomDrawerService.openDrawer(CodeEditorDrawerComponent, {
      initialState: {
        encodedCode: template.code,
        sourceSystem: 'Template',
        action: 'view'
      }
    });
  }

  getTransformationTypeName(templateType: string): string {
    switch (templateType) {
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
      default:
        return templateType;
    }
  }

  scrollToElement(elementId: string): void {
    const element = document.getElementById(elementId);
    if (element) {
      window.scrollTo({ top: element.offsetTop - 120, behavior: 'smooth' });
    }
  }
}
