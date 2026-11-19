import { NO_ERRORS_SCHEMA } from '@angular/core';
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
    const fixture = TestBed.createComponent(MappingTemplateStepComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('mapping', { direction: 'OUTBOUND', filterMapping: "type = 'c8y_Compass'" } as any);
    fixture.componentRef.setInput('stepperConfiguration', { direction: 'OUTBOUND', persistence: 'create' } as any);
    fixture.componentRef.setInput('sourceTemplate', { type: 'c8y_Compass', id: '1' });
    fixture.componentRef.setInput('targetTemplate', {});
    fixture.componentRef.setInput('filterFormly', new FormGroup({ filterMapping: new FormControl('') }));
    fixture.detectChanges(); // runs the input effects, which replaced ngOnChanges
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
    const fixture = TestBed.createComponent(MappingTemplateStepComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('mapping', { direction: 'OUTBOUND', filterMapping: "type = 'c8y_Compass'" } as any);
    fixture.componentRef.setInput('stepperConfiguration', { direction: 'OUTBOUND', persistence: 'create' } as any);
    fixture.componentRef.setInput('filterFormly', new FormGroup({ filterMapping: new FormControl('') }));
    // The editor exists but still shows the placeholder from before the templates were expanded.
    (c as any).editorSourceStepTemplate = () => ({ get: () => ({}) });
    fixture.componentRef.setInput('sourceTemplate', { type: 'c8y_Compass', id: '1' });
    fixture.detectChanges(); // runs the input effects, which replaced ngOnChanges
    await new Promise(r => setTimeout(r, 50));
    expect(c.filterModel.filterExpression?.result).toBe('true');
  });

  it('follows a filter that the host changes after the step was created (stepper: General settings)', async () => {
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
    const fixture = TestBed.createComponent(MappingTemplateStepComponent);
    const c = fixture.componentInstance;
    const filterFormly = new FormGroup({ filterMapping: new FormControl('') });
    // Created while the filter is still empty, as the stepper does before the user reaches General settings
    fixture.componentRef.setInput('mapping', { direction: 'OUTBOUND', filterMapping: '' } as any);
    fixture.componentRef.setInput('stepperConfiguration', { direction: 'OUTBOUND', persistence: 'create' } as any);
    fixture.componentRef.setInput('sourceTemplate', { type: 'c8y_Compass', id: '1' });
    fixture.componentRef.setInput('targetTemplate', {});
    fixture.componentRef.setInput('filterFormly', filterFormly);
    fixture.detectChanges();
    expect(c.filterModel.filterMapping ?? '').toBe('');

    // The user picks "Filter by type" in General settings: the host now has the expression
    fixture.componentRef.setInput('mapping', { direction: 'OUTBOUND', filterMapping: "type = 'c8y_Compass'" } as any);
    fixture.detectChanges();
    await new Promise(r => setTimeout(r, 50));

    expect(c.filterModel.filterMapping).toBe("type = 'c8y_Compass'");
    expect(filterFormly.get('filterMapping')?.value).toBe("type = 'c8y_Compass'");
    expect(c.filterModel.filterExpression?.result).toBe('true');

    // ... and clears it again
    fixture.componentRef.setInput('mapping', { direction: 'OUTBOUND', filterMapping: '' } as any);
    fixture.detectChanges();

    expect(c.filterModel.filterMapping).toBe('');
    expect(filterFormly.get('filterMapping')?.value).toBe('');
    expect(c.filterModel.filterExpression).toBeUndefined();
  });
});
