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

import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { MappingStepPropertiesComponent } from './mapping-properties.component';
import { Direction, Mapping, Qos, SharedService, StepperConfiguration } from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { MappingService } from '../core/mapping.service';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { FormatStringPipe } from '../../shared/misc/format-string.pipe';
import { EditorMode } from '../shared/stepper.model';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeMapping(): Mapping {
  return { direction: Direction.INBOUND, targetAPI: 'MEASUREMENT' } as unknown as Mapping;
}

function makeStepperConfiguration(): StepperConfiguration {
  return { direction: Direction.INBOUND, editorMode: EditorMode.CREATE };
}

async function createComponent(
  deployedConnectors?: string[]
): Promise<MappingStepPropertiesComponent> {
  const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
  const component = fixture.componentInstance;
  component.mapping = makeMapping();
  component.stepperConfiguration = makeStepperConfiguration();
  component.propertyFormly = new FormGroup({});
  if (deployedConnectors) {
    component.deploymentMapEntry = { identifier: 'mapping-1', connectors: deployedConnectors };
  }
  await component.ngOnInit();
  return component;
}

// ---------------------------------------------------------------------------
// MappingStepPropertiesComponent
// ---------------------------------------------------------------------------

describe('MappingStepPropertiesComponent', () => {
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockMappingService: jasmine.SpyObj<MappingService>;
  let mockStepperService: jasmine.SpyObj<MappingStepperService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockConnectorConfigurationService: jasmine.SpyObj<ConnectorConfigurationService>;

  beforeEach(() => {
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', ['getFeatures']);
    mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: true } as any);

    mockMappingService = jasmine.createSpyObj<MappingService>('MappingService', ['evaluateExpression']);

    mockStepperService = jasmine.createSpyObj<MappingStepperService>('MappingStepperService', ['raiseAlert', 'notifyMappingPropertyChanged']);

    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', ['clearAll', 'add', 'remove']);
    Object.defineProperty(mockAlertService, 'state', { value: [] });

    mockConnectorConfigurationService = jasmine.createSpyObj<ConnectorConfigurationService>(
      'ConnectorConfigurationService',
      ['getConfigurations', 'getSpecifications']
    );
    // One Kafka connector, whose specification stops at AT_LEAST_ONCE.
    mockConnectorConfigurationService.getConfigurations.and.returnValue(
      of([{ identifier: 'kafka-1', name: 'Kafka Prod', connectorType: 'KAFKA' }] as any)
    );
    mockConnectorConfigurationService.getSpecifications.and.returnValue(
      of([
        {
          connectorType: 'KAFKA',
          supportedQos: [Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE]
        }
      ] as any)
    );

    TestBed.configureTestingModule({
      imports: [MappingStepPropertiesComponent],
      providers: [
        { provide: SharedService, useValue: mockSharedService },
        { provide: MappingService, useValue: mockMappingService },
        { provide: MappingStepperService, useValue: mockStepperService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: ConnectorConfigurationService, useValue: mockConnectorConfigurationService },
        FormatStringPipe
      ]
    });

    // CoreModule (imported by the component) eagerly reaches into the c8y app-shell DI graph
    // that the test injector doesn't provide (NG0201). These specs exercise the component
    // class, not the template, so strip the template/imports instead — same pattern as
    // mapping-type-drawer.component.spec.ts.
    TestBed.overrideComponent(MappingStepPropertiesComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
  });

  describe('filter inventory evaluation', () => {
    it('accepts a boolean-valued expression, stores the result, and sets mapping.filterInventory', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const component = await createComponent();

      await component.updateFilterInventoryExpressionResult('type = "lora_device_type"');

      expect(component.filterInventoryModel.filterExpression.valid).toBeTrue();
      expect(component.filterInventoryModel.filterExpression.resultType).toBe('Boolean');
      expect(component.mapping.filterInventory).toBe('type = "lora_device_type"');
    });

    it('rejects a non-boolean-valued expression and surfaces a form control error instead of updating mapping.filterInventory', async () => {
      mockMappingService.evaluateExpression.and.resolveTo('not-a-boolean' as any);
      const component = await createComponent();
      component.propertyFormly.addControl('filterInventory', new (await import('@angular/forms')).FormControl(''));

      await component.updateFilterInventoryExpressionResult('type');

      expect(component.filterInventoryModel.filterExpression.valid).toBeFalse();
      expect(component.propertyFormly.get('filterInventory')?.errors).toEqual(
        jasmine.objectContaining({ validationError: jasmine.anything() })
      );
      expect(component.mapping.filterInventory).toBeUndefined();
    });

    it('delegates to the shared MappingStepperService.raiseAlert rather than a duplicated local implementation', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const component = await createComponent();

      await component.updateFilterInventoryExpressionResult('type = "x"');

      expect(mockStepperService.raiseAlert).toHaveBeenCalledWith(
        jasmine.objectContaining({ type: 'info' })
      );
    });
  });

  describe('target API change', () => {
    it('updates mapping.targetAPI and emits targetAPIChanged', async () => {
      const component = await createComponent();
      const emitSpy = spyOn(component.targetAPIChanged, 'emit');

      component.onTargetAPIChanged('EVENT');

      expect(component.mapping.targetAPI).toBe('EVENT');
      expect(emitSpy).toHaveBeenCalledWith('EVENT');
    });
  });

  describe('QoS description', () => {
    it('explains the selected level when every deployed connector supports it', async () => {
      const component = await createComponent(['kafka-1']);

      const description = component.describeQos(Qos.AT_LEAST_ONCE);

      expect(description).toContain('acknowledged only after');
      expect(description).not.toContain('NOTE');
    });

    it('warns which deployed connector clamps the selected level, and to what', async () => {
      const component = await createComponent(['kafka-1']);

      const description = component.describeQos(Qos.EXACTLY_ONCE);

      expect(description).toContain('Kafka Prod');
      expect(description).toContain('handled as at least once');
    });

    it('says nothing while no level is selected', async () => {
      const component = await createComponent(['kafka-1']);

      expect(component.describeQos(undefined as unknown as Qos)).toBe('');
    });

    it('omits the warning when the mapping is not deployed to any connector', async () => {
      const component = await createComponent();

      expect(component.describeQos(Qos.EXACTLY_ONCE)).not.toContain('NOTE');
      expect(mockConnectorConfigurationService.getConfigurations).not.toHaveBeenCalled();
    });
  });
});
