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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeDetectorRef } from '@angular/core';
import { BottomDrawerRef } from '@c8y/ngx-components';
import { ConnectorConfigurationDrawerComponent } from './connector-configuration-drawer.component';
import {
  ConnectorConfiguration,
  ConnectorProperty,
  ConnectorPropertyType,
  ConnectorSpecification,
  ConnectorType,
  Direction,
  Feature,
  SharedService
} from '../..';
import { FormatStringPipe } from '../../misc/format-string.pipe';

describe('ConnectorConfigurationDrawerComponent', () => {
  let component: ConnectorConfigurationDrawerComponent;
  let fixture: ComponentFixture<ConnectorConfigurationDrawerComponent>;
  let mockBottomDrawerRef: jasmine.SpyObj<BottomDrawerRef<any>>;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockCdr: jasmine.SpyObj<ChangeDetectorRef>;
  let mockFormatStringPipe: jasmine.SpyObj<FormatStringPipe>;

  const mockFeature: Feature = {
    outputMappingEnabled: true,
    externalExtensionsEnabled: true,
    userHasMappingAdminRole: true,
    userHasMappingCreateRole: true,
    pulsarAvailable: false,
    deviceIsolationMQTTServiceEnabled: false
  };

  const mockConfiguration: ConnectorConfiguration = {
    identifier: 'test-connector',
    name: 'Test Connector',
    connectorType: ConnectorType.MQTT,
    enabled: false,
    properties: {
      mqttHost: 'localhost',
      mqttPort: 1883
    }
  };

  const mockSpecifications: ConnectorSpecification[] = [
    {
      connectorType: ConnectorType.MQTT,
      name: 'MQTT',
      description: 'MQTT Connector',
      supportedDirections: [Direction.INBOUND, Direction.OUTBOUND],
      singleton: false,
      supportsWildcardInTopicInbound: true,
      supportsWildcardInTopicOutbound: true,
      properties: {
        mqttHost: {
          type: ConnectorPropertyType.STRING_PROPERTY,
          description: 'MQTT Host',
          required: true,
          order: 1,
          hidden: false,
          readonly: false
        },
        mqttPort: {
          type: ConnectorPropertyType.NUMERIC_PROPERTY,
          description: 'MQTT Port',
          required: true,
          defaultValue: 1883,
          order: 2,
          hidden: false,
          readonly: false
        },
        enableSSL: {
          type: ConnectorPropertyType.BOOLEAN_PROPERTY,
          description: 'Enable SSL',
          required: false,
          defaultValue: false,
          order: 3,
          hidden: false,
          readonly: false
        }
      }
    }
  ];

  beforeEach(async () => {
    // Create spy objects
    mockBottomDrawerRef = jasmine.createSpyObj('BottomDrawerRef', ['close']);
    mockSharedService = jasmine.createSpyObj('SharedService', ['getFeatures']);
    mockCdr = jasmine.createSpyObj('ChangeDetectorRef', ['detectChanges', 'markForCheck']);
    mockFormatStringPipe = jasmine.createSpyObj('FormatStringPipe', ['transform']);

    // Setup default return values
    mockSharedService.getFeatures.and.returnValue(Promise.resolve(mockFeature));
    mockFormatStringPipe.transform.and.returnValue('Formatted String');

    await TestBed.configureTestingModule({
      imports: [ConnectorConfigurationDrawerComponent],
      providers: [
        { provide: BottomDrawerRef, useValue: mockBottomDrawerRef },
        { provide: SharedService, useValue: mockSharedService },
        { provide: ChangeDetectorRef, useValue: mockCdr },
        { provide: FormatStringPipe, useValue: mockFormatStringPipe }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectorConfigurationDrawerComponent);
    component = fixture.componentInstance;

    // Set required inputs
    component.action = 'create';
    component.configuration = { ...mockConfiguration };
    component.specifications = mockSpecifications;
    component.configurationsCount = 1;
    component.allowedConnectors = [ConnectorType.MQTT, ConnectorType.KAFKA];
  });

  describe('Component Initialization', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should initialize with create mode', async () => {
      component.action = 'create';
      await component.ngOnInit();

      expect(component.mode).toBe('Add');
      expect(component.readOnly).toBe(false);
    });

    it('should initialize with update mode', async () => {
      component.action = 'update';
      await component.ngOnInit();

      expect(component.mode).toBe('Update');
    });

    it('should initialize with view mode', async () => {
      component.action = 'view';
      await component.ngOnInit();

      expect(component.mode).toBe('View');
      expect(component.readOnly).toBe(true);
    });

    it('should load features on init', async () => {
      await component.ngOnInit();

      expect(mockSharedService.getFeatures).toHaveBeenCalled();
      expect(component.feature).toEqual(mockFeature);
    });

    it('should set readonly when configuration is enabled', async () => {
      component.configuration.enabled = true;
      await component.ngOnInit();

      expect(component.readOnly).toBe(true);
    });

    it('should initialize broker form fields', async () => {
      await component.ngOnInit();

      expect(component.brokerFormFields.length).toBeGreaterThan(0);
      expect(component.brokerFormFields[0].key).toBe('connectorType');
    });

    it('should create dynamic form for existing configuration', async () => {
      component.action = 'update';
      await component.ngOnInit();

      expect(component.dynamicFormFields.length).toBeGreaterThan(0);
    });
  });

  describe('Connector Type Selection', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should filter out CUMULOCITY_MQTT_SERVICE from connector options', () => {
      const connectorTypeField = component.brokerFormFields[0];
      const options = connectorTypeField.props?.options as any[];

      const hasCumulocityMQTT = options?.some(opt =>
        opt.value === ConnectorType.CUMULOCITY_MQTT_SERVICE
      );

      expect(hasCumulocityMQTT).toBe(false);
    });

    it('should disable connectors not in allowedConnectors list', () => {
      const connectorTypeField = component.brokerFormFields[0];
      const options = connectorTypeField.props?.options as any[];

      const mqttOption = options?.find(opt => opt.value === ConnectorType.MQTT);
      expect(mqttOption?.disabled).toBe(false);
    });

    it('should set connector description when type changes', async () => {
      component['setConnectorDescription']();

      expect(component.description).toBe('MQTT Connector');
      expect(component.configuration['description']).toBe('MQTT Connector');
    });
  });

  describe('Dynamic Form Creation', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should create form fields for all properties', async () => {
      await component['createDynamicForm'](ConnectorType.MQTT);

      // Should have basic fields (name, description) + property fields
      expect(component.dynamicFormFields.length).toBeGreaterThan(2);
    });

    it('should sort fields by order', async () => {
      await component['createDynamicForm'](ConnectorType.MQTT);

      // Fields should be sorted, basic fields first, then properties by order
      const fieldWithOrder1 = component.dynamicFormFields.find(
        f => f.fieldGroup?.[0]?.key === 'properties.mqttHost'
      );
      const fieldWithOrder2 = component.dynamicFormFields.find(
        f => f.fieldGroup?.[0]?.key === 'properties.mqttPort'
      );

      expect(fieldWithOrder1).toBeDefined();
      expect(fieldWithOrder2).toBeDefined();
    });

    it('should set default values for create action', async () => {
      component.action = 'create';
      await component['createDynamicForm'](ConnectorType.MQTT);

      expect(component.configuration.properties['mqttPort']).toBe(1883);
      expect(component.configuration.properties['enableSSL']).toBe(false);
    });

    it('should exclude hidden properties from form', async () => {
      // Add a hidden property to spec
      mockSpecifications[0].properties['hiddenProp'] = {
        type: ConnectorPropertyType.STRING_PROPERTY,
        description: 'Hidden',
        required: false,
        order: 99,
        hidden: true,
        readonly: false
      };

      await component['createDynamicForm'](ConnectorType.MQTT);

      const hasHiddenField = component.dynamicFormFields.some(
        f => f.fieldGroup?.[0]?.key === 'properties.hiddenProp'
      );

      expect(hasHiddenField).toBe(false);
    });

    it('should trigger change detection after creating form', async () => {
      await component['createDynamicForm'](ConnectorType.MQTT);

      expect(mockCdr.detectChanges).toHaveBeenCalled();
    });
  });

  describe('Property Type Field Creation', () => {
    let mockEntry: { key: string; property: ConnectorProperty };

    beforeEach(() => {
      mockEntry = {
        key: 'testField',
        property: {
          type: ConnectorPropertyType.STRING_PROPERTY,
          description: 'Test field',
          required: true,
          order: 1,
          hidden: false,
          readonly: false
        }
      };
    });

    it('should create numeric field', () => {
      mockEntry.property.type = ConnectorPropertyType.NUMERIC_PROPERTY;
      const field = component['createNumericField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('input');
      expect(field.fieldGroup?.[0]?.props?.type).toBe('number');
    });

    it('should create string field', () => {
      const field = component['createStringField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('input');
      expect(field.fieldGroup?.[0]?.props?.label).toBe('testField');
    });

    it('should create sensitive string field with password type', () => {
      mockEntry.property.type = ConnectorPropertyType.SENSITIVE_STRING_PROPERTY;
      const field = component['createSensitiveStringField'](mockEntry);

      expect(field.fieldGroup?.[0]?.props?.type).toBe('password');
    });

    it('should create boolean field as switch', () => {
      mockEntry.property.type = ConnectorPropertyType.BOOLEAN_PROPERTY;
      const field = component['createBooleanField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('switch');
    });

    it('should create option field with dropdown', () => {
      mockEntry.property.type = ConnectorPropertyType.OPTION_PROPERTY;
      (mockEntry.property as any).options = { OPT1: 'Option 1', OPT2: 'Option 2' };

      const field = component['createOptionField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('select');
      expect(field.fieldGroup?.[0]?.props?.options).toBeDefined();
    });

    it('should create large string field as textarea', () => {
      mockEntry.property.type = ConnectorPropertyType.STRING_LARGE_PROPERTY;
      const field = component['createLargeStringField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('d11r-textarea');
      expect(field.fieldGroup?.[0]?.props?.rows).toBe(6);
    });

    it('should create map field with input list', () => {
      mockEntry.property.type = ConnectorPropertyType.MAP_PROPERTY;
      const field = component['createMapField'](mockEntry);

      expect(field.fieldGroup?.[0]?.type).toBe('d11r-input-list');
      expect(field.fieldGroup?.[0]?.defaultValue).toEqual({});
    });

    it('should mark required fields', () => {
      mockEntry.property.required = true;
      const field = component['createStringField'](mockEntry);

      expect(field.fieldGroup?.[0]?.props?.required).toBe(true);
    });

    it('should mark readonly fields', () => {
      mockEntry.property.readonly = true;
      const field = component['createStringField'](mockEntry);

      expect(field.fieldGroup?.[0]?.props?.disabled).toBe(true);
    });
  });

  describe('Conditional Field Display', () => {
    it('should create hide expression for conditional properties', () => {
      const mockEntry = {
        key: 'conditionalField',
        property: {
          type: ConnectorPropertyType.STRING_PROPERTY,
          description: 'Conditional',
          required: false,
          order: 1,
          hidden: false,
          readonly: false,
          condition: {
            key: 'enableSSL',
            anyOf: ['true']
          }
        }
      };

      const hideExpression = component['createHideExpression'](mockEntry.property);
      const model = { properties: { enableSSL: false } };

      expect(hideExpression(model)).toBe(true);
    });

    it('should convert boolean strings in conditions', () => {
      const result = component['convertBooleanStrings'](['true', 'false', 'other']);

      expect(result).toEqual([true, false, 'other']);
    });
  });

  describe('Save and Cancel Operations', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should save configuration on onSave', () => {
      const saveSpy = jasmine.createSpy('save');
      component['_save'] = saveSpy;

      component.onSave();

      expect(saveSpy).toHaveBeenCalledWith(component.configuration);
      expect(mockBottomDrawerRef.close).toHaveBeenCalled();
    });

    it('should cancel on onCancel', () => {
      const cancelSpy = jasmine.createSpy('cancel');
      component['_cancel'] = cancelSpy;

      component.onCancel();

      expect(cancelSpy).toHaveBeenCalledWith('User canceled');
      expect(mockBottomDrawerRef.close).toHaveBeenCalled();
    });

    it('should resolve promise when saved', (done) => {
      component.result.then(config => {
        expect(config).toEqual(component.configuration);
        done();
      });

      component.onSave();
    });

    it('should reject promise when canceled', (done) => {
      component.result.catch(reason => {
        expect(reason).toBe('User canceled');
        done();
      });

      component.onCancel();
    });
  });

  describe('Form Validation', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should validate and reset form on onValidate', () => {
      spyOn(component.dynamicForm, 'updateValueAndValidity');
      spyOn(component.dynamicForm, 'reset');

      component.onValidate();

      expect(component.dynamicForm.updateValueAndValidity).toHaveBeenCalled();
      expect(component.dynamicForm.reset).toHaveBeenCalled();
    });
  });

  describe('Default Configuration', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should set default name for new connector', () => {
      component['setDefaultConfiguration'](ConnectorType.MQTT);

      expect(component.configuration.name).toContain('Formatted String');
      expect(component.configuration.name).toContain('01');
    });

    it('should format WEB_HOOK_INTERNAL as Cumulocity API', () => {
      component['setDefaultConfiguration'](ConnectorType.WEB_HOOK_INTERNAL);

      expect(component.configuration.name).toContain('Cumulocity API');
    });

    it('should set enabled to false for new connectors', () => {
      component['setDefaultConfiguration'](ConnectorType.MQTT);

      expect(component.configuration.enabled).toBe(false);
    });

    it('should pad configuration count with leading zeros', () => {
      component.configurationsCount = 5;
      component['setDefaultConfiguration'](ConnectorType.MQTT);

      expect(component.configuration.name).toContain('06');
    });
  });

  describe('Edge Cases', () => {
    it('should handle missing specification gracefully', async () => {
      component.specifications = [];
      await component.ngOnInit();

      // Should not throw error
      expect(component.description).toBeUndefined();
    });

    it('should handle properties without order', async () => {
      const specWithoutOrder: ConnectorSpecification = {
        ...mockSpecifications[0],
        properties: {
          prop1: {
            type: ConnectorPropertyType.STRING_PROPERTY,
            description: 'No order',
            required: false,
            order: 1,
            hidden: false,
            readonly: false
          }
        }
      };

      component.specifications = [specWithoutOrder];
      await component['createDynamicForm'](ConnectorType.MQTT);

      // Should not throw error
      expect(component.dynamicFormFields.length).toBeGreaterThan(0);
    });

    it('should warn for unsupported property types', async () => {
      spyOn(console, 'warn');

      const specWithUnsupportedType: ConnectorSpecification = {
        ...mockSpecifications[0],
        properties: {
          unsupportedProp: {
            type: 'UNSUPPORTED_TYPE' as any,
            description: 'Unsupported',
            required: false,
            order: 1,
            hidden: false,
            readonly: false
          }
        }
      };

      component.specifications = [specWithUnsupportedType];
      await component['createDynamicForm'](ConnectorType.MQTT);

      expect(console.warn).toHaveBeenCalledWith(
        jasmine.stringContaining('Unsupported property type')
      );
    });
  });
});
