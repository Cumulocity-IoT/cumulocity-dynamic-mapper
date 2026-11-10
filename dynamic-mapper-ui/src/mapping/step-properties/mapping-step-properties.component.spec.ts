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

import { provideTranslateStub } from '../../shared/testing/translate-stub';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { MappingStepPropertiesComponent } from './mapping-step-properties.component';
import { Direction, Mapping, Qos, SharedService, StepperConfiguration } from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { MappingService } from '../core/mapping.service';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { FormatStringPipe } from '../../shared/misc/format-string.pipe';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeMapping(): Mapping {
  return { direction: Direction.INBOUND, targetAPI: 'MEASUREMENT' } as unknown as Mapping;
}

function makeStepperConfiguration(): StepperConfiguration {
  return { direction: Direction.INBOUND, persistence: 'create' };
}

async function createComponent(
  deployedConnectors?: string[]
): Promise<MappingStepPropertiesComponent> {
  const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
  const component = fixture.componentInstance;
  component.mapping = makeMapping();
  component.stepperConfiguration = makeStepperConfiguration();
  component.propertyFormly = new FormGroup({});
  // Act as the host: apply every patch to the mapping.
  component.propertiesChange.subscribe(patch => Object.assign(component.mapping, patch));
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
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', ['getFeatures', 'getServiceConfiguration']);
    mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: true } as any);
    mockSharedService.getServiceConfiguration.and.resolveTo({ inventoryFragmentsToCache: ['type'] } as any);

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
        FormatStringPipe,
        provideTranslateStub()
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

  describe('execution filter control (outbound)', () => {
    async function outboundComponent(filterMapping?: string) {
      const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
      const component = fixture.componentInstance;
      component.mapping = { direction: Direction.OUTBOUND, targetAPI: 'MEASUREMENT', filterMapping } as unknown as Mapping;
      component.stepperConfiguration = { direction: Direction.OUTBOUND, persistence: 'create' };
      component.propertyFormly = new FormGroup({});
      component.propertiesChange.subscribe(patch => Object.assign(component.mapping, patch));
      await component.ngOnInit();
      return component;
    }

    it('opens a new mapping with nothing chosen, and requires the choice and its value', async () => {
      const component = await outboundComponent('');
      expect(component.model.executionFilterMode).toBeUndefined();
      const fields = (component.propertyFormlyFields as any[]).flatMap(f => f.fieldGroup ?? []);
      const mode = fields.find(f => f.key === 'executionFilterMode');
      const value = fields.find(f => f.key === 'executionFilterValue');
      expect(mode.props.required).toBeTrue();
      expect(value.props.required).toBeTrue();
      // no value field until an option is chosen, and none for the custom option
      expect(value.expressions.hide()).toBeTrue();
      component.model.executionFilterMode = 'type';
      expect(value.expressions.hide()).toBeFalse();
      component.model.executionFilterMode = 'custom';
      expect(value.expressions.hide()).toBeTrue();
    });

    it('opens a stored type expression as "type" with its value', async () => {
      const component = await outboundComponent("type = 'lora'");
      expect(component.model.executionFilterMode).toBe('type');
      expect(component.model.executionFilterValue).toBe('lora');
    });

    it('opens a stored $exists expression as "fragment"', async () => {
      const component = await outboundComponent('$exists(c8y_Temperature)');
      expect(component.model.executionFilterMode).toBe('fragment');
      expect(component.model.executionFilterValue).toBe('c8y_Temperature');
    });

    it('opens any other expression as "custom" and leaves it untouched', async () => {
      const component = await outboundComponent("type = 'a' and $exists(b)");
      expect(component.model.executionFilterMode).toBe('custom');
      expect(component.mapping.filterMapping).toBe("type = 'a' and $exists(b)");
    });

    it('writes the expression to filterMapping when the user changes the control', async () => {
      const component = await outboundComponent('true');
      component.model.executionFilterMode = 'fragment';
      component.model.executionFilterValue = 'c8y_Temperature';
      component.onExecutionFilterChanged();
      expect(component.mapping.filterMapping).toBe('$exists(c8y_Temperature)');

      component.model.executionFilterMode = 'type';
      component.model.executionFilterValue = 'lora';
      component.onExecutionFilterChanged();
      expect(component.mapping.filterMapping).toBe("type = 'lora'");
    });

    it('shows the custom expression read-only in the value column', async () => {
      const component = await outboundComponent("type = 'a' and $exists(b)");
      const field = (component.propertyFormlyFields as any[])
        .flatMap(f => f.fieldGroup ?? [])
        .find(f => f.key === 'filterMapping');
      expect(field.props.readonly).toBeTrue();
      expect(field.expressions.hide()).toBeFalse();
      expect(component.model.filterMapping).toBe("type = 'a' and $exists(b)");

      component.model.executionFilterMode = 'type';
      expect(field.expressions.hide()).toBeTrue();
    });

    it('follows a filterMapping edited in the Templates step, and never writes the stale one back', async () => {
      const component = await outboundComponent("type = 'lora'");
      component.mapping.filterMapping = '$exists(c8y_Temperature)';
      component.ngDoCheck();
      expect(component.model.executionFilterMode).toBe('fragment');
      expect(component.model.executionFilterValue).toBe('c8y_Temperature');

      component.mapping.filterMapping = "type = 'a' and $exists(b)";
      component.model.name = 'renamed';
      component.commitModel();
      expect(component.mapping.filterMapping).toBe("type = 'a' and $exists(b)");
    });

    it('keeps a chosen option with an empty value while the user types', async () => {
      const component = await outboundComponent('');
      component.model.executionFilterMode = 'type';
      component.onExecutionFilterChanged();
      component.ngDoCheck();
      expect(component.model.executionFilterMode).toBe('type');
    });

    it('clears filterMapping for the custom option', async () => {
      const component = await outboundComponent("type = 'lora'");
      component.model.executionFilterMode = 'custom';
      component.onExecutionFilterChanged();
      expect(component.mapping.filterMapping).toBe('');
    });
  });

  describe('inventory filter behind Expert Mode', () => {
    async function component(filterInventory?: string) {
      const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
      const c = fixture.componentInstance;
      c.mapping = { direction: Direction.OUTBOUND, targetAPI: 'MEASUREMENT', filterInventory } as unknown as Mapping;
      c.stepperConfiguration = { direction: Direction.OUTBOUND, persistence: 'create' };
      c.propertyFormly = new FormGroup({});
      await c.ngOnInit();
      return c;
    }
    const field = (c: MappingStepPropertiesComponent) =>
      (c.propertyFormlyFields as any[]).flatMap(f => f.fieldGroup ?? []).find(f => f.key === 'filterInventory');

    it('hides the field by default, keeps its value on hide, and labels it "Inventory filter"', async () => {
      const c = await component('');
      expect(c.model.expertMode).toBeFalse();
      expect(field(c).expressions.hide()).toBeTrue();
      expect(field(c).resetOnHide).toBeFalse();
      expect(field(c).props.label).toBe('Inventory filter');
      c.model.expertMode = true;
      expect(field(c).expressions.hide()).toBeFalse();
    });

    it('opens with Expert Mode on when a filter is already stored', async () => {
      const c = await component('type = "x"');
      expect(c.model.expertMode).toBeTrue();
    });
  });

  describe('filter inventory: attributes missing from the inventory cache', () => {
    /** A stand-in for the formly field, which is not rendered in these template-less specs. */
    async function componentWithField() {
      const component = await createComponent();
      const field: any = { props: {}, options: { detectChanges: jasmine.createSpy('detectChanges') } };
      (component as any).filterInventoryField = field;
      return { component, field };
    }

    it('warns under the field about an attribute that is not cached', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component, field } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('type = "a" and c8y_Hardware.model = "x"');

      expect(component.filterInventoryUncached).toEqual(['c8y_Hardware']);
      expect(field.props.warning).toContain('Not in the inventory cache: c8y_Hardware');
      expect(field.options.detectChanges).toHaveBeenCalledWith(field);
    });

    it('does not warn when every attribute is cached', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component, field } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('type = "a"');

      expect(component.filterInventoryUncached).toEqual([]);
      expect(field.props.warning).toBeUndefined();
    });

    it('honours glob entries of the cache configuration', async () => {
      mockSharedService.getServiceConfiguration.and.resolveTo({ inventoryFragmentsToCache: ['type', 'c8y_*'] } as any);
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('c8y_Hardware.model = "x"');

      expect(component.filterInventoryUncached).toEqual([]);
    });

    it('shows the warning inside the field and not as a toast', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('name = "x"');

      expect(component.filterInventoryUncached).toEqual(['name']);
      expect(mockStepperService.raiseAlert).not.toHaveBeenCalled();
      expect(mockAlertService.add).not.toHaveBeenCalled();
    });

    it('removes the warning again once the expression only uses cached attributes', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component, field } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('name = "x"');
      expect(field.props.warning).toContain('Not in the inventory cache');

      await component.updateFilterInventoryExpressionResult('type = "x"');
      expect(field.props.warning).toBeUndefined();
      expect(component.filterInventoryUncached).toEqual([]);
    });

    it('is silent when the service configuration cannot be read', async () => {
      mockSharedService.getServiceConfiguration.and.rejectWith(new Error('403'));
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component, field } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('name = "x"');

      expect(component.filterInventoryUncached).toEqual([]);
      expect(field.props.warning).toBeUndefined();
    });

    it('names every missing attribute, pluralised', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const { component, field } = await componentWithField();

      await component.updateFilterInventoryExpressionResult('ty = "1" and name = "x"');

      expect(field.props.warning).toContain('Not in the inventory cache: ty, name.');
      expect(field.props.warning).toContain('as if the device did not have them');
    });

    it('does not warn for an expression that is not valid', async () => {
      mockMappingService.evaluateExpression.and.rejectWith(new Error('Unexpected end of expression'));
      const { component } = await componentWithField();
      component.propertyFormly.addControl('filterInventory', new (await import('@angular/forms')).FormControl(''));

      await component.updateFilterInventoryExpressionResult('name = ');

      expect(component.filterInventoryUncached).toEqual([]);
    });
  });

  describe('rendering before ngOnInit has finished', () => {
    // Regression: ngOnInit awaits before it set up the filter model, and the template reads
    // filterInventoryModel.filterExpression on the first change-detection pass in between
    // ("Cannot read properties of undefined (reading 'filterExpression')").
    it('has the filter model from construction on', () => {
      const component = TestBed.createComponent(MappingStepPropertiesComponent).componentInstance;

      expect(component.filterInventoryModel.filterExpression.resultType).toBe('empty');
    });
  });

  describe('filter inventory evaluation', () => {
    it('accepts a boolean-valued expression, stores the result, and sets mapping.filterInventory', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const component = await createComponent();
      const emitted: Partial<Mapping>[] = [];
      component.propertiesChange.subscribe(patch => emitted.push(patch));

      await component.updateFilterInventoryExpressionResult('type = "lora_device_type"');

      expect(component.filterInventoryModel.filterExpression.valid).toBeTrue();
      expect(component.filterInventoryModel.filterExpression.resultType).toBe('Boolean');
      expect(component.model.filterInventory).toBe('type = "lora_device_type"');
      expect(emitted).toContain(jasmine.objectContaining({ filterInventory: 'type = "lora_device_type"' }));
      expect(component.mapping.filterInventory).toBe('type = "lora_device_type"'); // applied by the host stand-in
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
      expect(component.model.filterInventory).toBeUndefined();
      expect(component.mapping.filterInventory).toBeUndefined();
    });

    it('does not raise the "fragments to cache" note as a toast: it is part of the field help text', async () => {
      mockMappingService.evaluateExpression.and.resolveTo(true as any);
      const component = await createComponent();

      await component.updateFilterInventoryExpressionResult('type = "x"');

      expect(mockStepperService.raiseAlert).not.toHaveBeenCalled();
    });
  });

  describe('target API change', () => {
    it('emits the new target API as a patch and as targetAPIChanged', async () => {
      const component = await createComponent();
      const emitSpy = spyOn(component.targetAPIChanged, 'emit');
      const patches: Partial<Mapping>[] = [];
      component.propertiesChange.subscribe(patch => patches.push(patch));

      component.onTargetAPIChanged('EVENT');

      expect(patches).toEqual([{ targetAPI: 'EVENT' } as Partial<Mapping>]);
      expect(emitSpy).toHaveBeenCalledWith('EVENT');
    });
  });

  describe('working copy', () => {
    it('does not modify the host mapping during init, and leaves a mapping without direction as it is', async () => {
      const mapping = makeMapping();
      delete (mapping as any).direction;
      const before = JSON.stringify(mapping);
      const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
      const component = fixture.componentInstance;
      component.mapping = mapping;
      component.stepperConfiguration = makeStepperConfiguration();
      component.propertyFormly = new FormGroup({});

      await component.ngOnInit();

      expect(JSON.stringify(mapping)).toBe(before);
      expect(component.targetSystem).toBe('Cumulocity');
    });

    it('announces the default external id type once, as a patch', async () => {
      const fixture = TestBed.createComponent(MappingStepPropertiesComponent);
      const component = fixture.componentInstance;
      component.mapping = makeMapping();
      component.stepperConfiguration = makeStepperConfiguration();
      component.propertyFormly = new FormGroup({});
      const patches: Partial<Mapping>[] = [];
      component.propertiesChange.subscribe(patch => patches.push(patch));

      await component.ngOnInit();

      expect(patches).toEqual([{ externalIdType: 'c8y_Serial' } as Partial<Mapping>]);
    });

    it('emits only the properties that differ, and nothing when the copy matches the mapping', async () => {
      const component = await createComponent();
      const patches: Partial<Mapping>[] = [];
      component.propertiesChange.subscribe(patch => patches.push(patch));
      component.mapping.externalIdType = 'c8y_Serial';

      component.commitModel();
      expect(patches).toEqual([]);

      component.model.name = 'renamed';
      component.model.qos = Qos.AT_LEAST_ONCE;
      component.commitModel();
      expect(patches).toEqual([{ name: 'renamed', qos: Qos.AT_LEAST_ONCE } as Partial<Mapping>]);
    });

    it('never carries mapping state outside its own properties, so the host can change it meanwhile', async () => {
      const component = await createComponent();
      component.mapping.substitutions = [{ pathSource: 'a', pathTarget: 'b' } as any];
      const patches: Partial<Mapping>[] = [];
      component.propertiesChange.subscribe(patch => patches.push(patch));

      component.model.name = 'x';
      component.commitModel();

      expect(Object.keys(patches[0])).toEqual(['name']);
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
