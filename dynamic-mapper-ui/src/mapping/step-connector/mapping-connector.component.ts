/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation, input, model } from '@angular/core';
import { C8yTranslateDirective } from '@c8y/ngx-components';
import {
  ConnectorGridComponent,
  DeploymentMapEntry,
  Direction,
  StepperConfiguration
} from '../../shared';

@Component({
  selector: 'd11r-mapping-connector',
  templateUrl: 'mapping-connector.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css', './mapping-connector.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ConnectorGridComponent, C8yTranslateDirective]
})
export class MappingConnectorComponent implements OnInit {
  readonly showHeader = input<boolean>(false);
  readonly stepperConfiguration = input<StepperConfiguration>();
  readonly directions = input<Direction[]>([Direction.INBOUND, Direction.OUTBOUND]);
  /** Two-way: setting it announces `deploymentMapEntryChange` to the host. */
  readonly deploymentMapEntry = model<DeploymentMapEntry>();

  readOnly: boolean;

  ngOnInit(): void {
    this.readOnly = !!this.stepperConfiguration().readOnly;
  }

  onDeploymentMapEntryChanged(value: DeploymentMapEntry): void {
    this.deploymentMapEntry.set(value);
  }
}
