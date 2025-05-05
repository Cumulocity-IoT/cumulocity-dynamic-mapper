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

import { Component, OnInit, ViewChild } from '@angular/core';
import { MappingService } from '../mapping/core/mapping.service';
import { Direction, JsonEditorComponent, NODE1, NODE3 } from '../shared';
import { BehaviorSubject, from, Subject } from 'rxjs';
import { ConnectorConfigurationService } from '../connector';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

@Component({
  selector: 'd11r-landing',
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.css',
  standalone: false
})
export class LandingComponent implements OnInit {
  constructor(
    private mappingService: MappingService,
    private connectorConfigurationService: ConnectorConfigurationService,
    private sanitizer: DomSanitizer
  ) {
    this.linkSVG = this.sanitizer.bypassSecurityTrustUrl(
      'image/Dynamic_Mapper_Snooping_Stepper_Process.svg'
    );
  }
  @ViewChild('editorTest', { static: false }) editorTest: JsonEditorComponent;

  ROUTE_INBOUND: string = `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`;
  ROUTE_OUTBOUND: string =
    `/sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outbound`;
  ROUTE_CONNECTORS: string = `/sag-ps-pkg-dynamic-mapping/${NODE3}/connectorConfiguration`;
  countMappingInbound$: Subject<any> = new BehaviorSubject<any>(0);
  countMappingOutbound$: Subject<any> = new BehaviorSubject<any>(0);
  countConnector$: Subject<any> = new BehaviorSubject<any>(0);
  linkSnoopProcess: string = '';
  linkSVG: SafeResourceUrl;

  ngOnInit(): void {
    this.linkSnoopProcess = '/apps/sag-ps-pkg-dynamic-mapping/image/Dynamic_Mapper_Snooping_Stepper_Process.svg';
    from(this.mappingService.getMappings(Direction.INBOUND)).subscribe(
      (mappings) => {
        this.countMappingInbound$.next(!mappings ? 'no' : mappings.length);
      }
    );

    from(this.mappingService.getMappings(Direction.OUTBOUND)).subscribe(
      (count) => this.countMappingOutbound$.next(!count ? 'no' : count.length)
    );

    from(
      this.connectorConfigurationService.getConnectorConfigurations()
    ).subscribe((count) =>
      this.countConnector$.next(!count ? 'no' : count.length)
    );
  }

}
