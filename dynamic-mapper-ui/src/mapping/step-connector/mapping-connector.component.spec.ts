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

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BsModalService } from 'ngx-bootstrap/modal';
import { ConnectorConfigurationService } from '../../connector';
import { DeploymentMapEntry, SharedService } from '../../shared';
import { MappingConnectorComponent } from './mapping-connector.component';
import { EditorMode } from '../shared/stepper.model';

/**
 * Unit tests for {@link MappingConnectorComponent}. It is a thin wrapper around the connector
 * grid; the logic worth pinning is the read-only derivation from the editor mode and the
 * pass-through of deployment-map-entry changes to the parent.
 */
describe('MappingConnectorComponent', () => {
  let component: MappingConnectorComponent;
  let fixture: ComponentFixture<MappingConnectorComponent>;

  beforeEach(async () => {
    TestBed.overrideComponent(MappingConnectorComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingConnectorComponent],
      providers: [
        { provide: SharedService, useValue: jasmine.createSpyObj('SharedService', ['getFeatures']) },
        { provide: BsModalService, useValue: jasmine.createSpyObj('BsModalService', ['show']) },
        {
          provide: ConnectorConfigurationService,
          useValue: jasmine.createSpyObj('ConnectorConfigurationService', ['getConnectorConfigurations'])
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingConnectorComponent);
    component = fixture.componentInstance;
  });

  it('is read-only in READ_ONLY editor mode', async () => {
    component.stepperConfiguration = { editorMode: EditorMode.READ_ONLY };
    await component.ngOnInit();
    expect(component.readOnly).toBe(true);
  });

  it('is editable in CREATE editor mode', async () => {
    component.stepperConfiguration = { editorMode: EditorMode.CREATE };
    await component.ngOnInit();
    expect(component.readOnly).toBe(false);
  });

  it('stores and re-emits deployment-map-entry changes', (done) => {
    const entry: DeploymentMapEntry = { identifier: 'm', connectors: ['c1'] };
    component.deploymentMapEntryChange.subscribe((value) => {
      expect(value).toBe(entry);
      expect(component.deploymentMapEntry).toBe(entry);
      done();
    });
    component.onDeploymentMapEntryChanged(entry);
  });
});
