import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import { MappingTemplateStepComponent } from './mapping-template-step.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { MappingService } from '../core/mapping.service';
import { SharedService } from '../../shared';
import { ExtensionService } from '../../extension';
import { AiAgentService } from '../core/ai-agent.service';
import { provideTranslateStub } from '../../shared/testing/translate-stub';

describe('MappingTemplateStepComponent initial filter evaluation', () => {
  it('evaluates a prefilled filter on first render', async () => {
    const jsonata = require('jsonata');
    TestBed.overrideComponent(MappingTemplateStepComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
    await TestBed.configureTestingModule({
      imports: [MappingTemplateStepComponent],
      providers: [
        MappingStepperService,
        { provide: MappingService, useValue: { evaluateExpression: async (j: any, p: string) => jsonata(p).evaluate(j) } },
        { provide: SharedService, useValue: {} },
        { provide: ExtensionService, useValue: {} },
        { provide: AiAgentService, useValue: {} },
        { provide: AlertService, useValue: { add() {}, remove() {}, state: [] } },
        provideTranslateStub()
      ]
    }).compileComponents();
    const c = TestBed.createComponent(MappingTemplateStepComponent).componentInstance;
    c.mapping = { direction: 'OUTBOUND', filterMapping: "type = 'c8y_Compass'" } as any;
    c.stepperConfiguration = { direction: 'OUTBOUND', persistence: 'create' } as any;
    c.sourceTemplate = { type: 'c8y_Compass', id: '1' };
    c.targetTemplate = {};
    c.filterFormly = new FormGroup({ filterMapping: new FormControl('') });
    c.ngOnChanges({
      filterFormly: new SimpleChange(undefined, c.filterFormly, true),
      sourceTemplate: new SimpleChange(undefined, c.sourceTemplate, true),
      mapping: new SimpleChange(undefined, c.mapping, true)
    });
    await new Promise(r => setTimeout(r, 50));
    expect(c.filterModel.filterExpression).toEqual({ result: 'true', resultType: 'Boolean', valid: true });
  });

  it('evaluates against the new source template input, not the editor that still holds the old content', async () => {
    const jsonata = require('jsonata');
    TestBed.overrideComponent(MappingTemplateStepComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
    await TestBed.configureTestingModule({
      imports: [MappingTemplateStepComponent],
      providers: [
        MappingStepperService,
        { provide: MappingService, useValue: { evaluateExpression: async (j: any, p: string) => jsonata(p).evaluate(j) } },
        { provide: SharedService, useValue: {} },
        { provide: ExtensionService, useValue: {} },
        { provide: AiAgentService, useValue: {} },
        { provide: AlertService, useValue: { add() {}, remove() {}, state: [] } },
        provideTranslateStub()
      ]
    }).compileComponents();
    const c = TestBed.createComponent(MappingTemplateStepComponent).componentInstance;
    c.mapping = { direction: 'OUTBOUND', filterMapping: "type = 'c8y_Compass'" } as any;
    c.stepperConfiguration = { direction: 'OUTBOUND', persistence: 'create' } as any;
    c.filterFormly = new FormGroup({ filterMapping: new FormControl('') });
    // The editor exists but still shows the placeholder from before the templates were expanded.
    (c as any).editorSourceStepTemplate = { get: () => ({}) };
    c.sourceTemplate = { type: 'c8y_Compass', id: '1' };
    c.ngOnChanges({ sourceTemplate: new SimpleChange({}, c.sourceTemplate, false) });
    await new Promise(r => setTimeout(r, 50));
    expect(c.filterModel.filterExpression?.result).toBe('true');
  });
});
