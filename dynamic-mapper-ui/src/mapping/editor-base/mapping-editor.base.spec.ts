/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

// Side-effect import first: shared/mapping/* have a circular import that only resolves when the
// `shared` barrel is evaluated before them (see mapping-stepper.component.ts).
import '../../shared';
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { DeploymentMapEntry, Direction, Mapping, MappingType, StepperConfiguration, TransformationType } from '../../shared';
import { stringToBase64 } from '../../shared/mapping/util';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { MappingEditorBase } from './mapping-editor.base';
import { provideTranslateStub } from '../../shared/testing/translate-stub';

@Component({ selector: 'd11r-test-editor-host', template: '', standalone: true })
class TestEditorHostComponent extends MappingEditorBase {
  mapping = {
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.JSONATA,
    substitutions: [],
    sourceTemplate: '{}',
    targetTemplate: '{}'
  } as unknown as Mapping;
  stepperConfiguration = { direction: Direction.INBOUND, persistence: 'create' } as StepperConfiguration;
  templateStepRef = undefined as any;
  codeEditor = undefined as any;
  currentStepIndex = 0;
  protected readonly substitutionStepIndex = 3;

  // protected members under test
  seed() { this.seedMappingCode(); }
  reload() { return this.reloadExtensions(); }
  enterTransformationStep() { this.enterTransformation(); }
  enterTestingStep() { this.enterTesting(); }
  enqueue(handler: () => Promise<void>) { return this.enqueueTransition(handler); }
}

describe('MappingEditorBase', () => {
  let host: TestEditorHostComponent;
  let stepperService: jasmine.SpyObj<MappingStepperService>;

  beforeEach(() => {
    stepperService = jasmine.createSpyObj<MappingStepperService>('MappingStepperService', [
      'loadExtensions', 'computeExtensionItems', 'refreshSubstitutionValidity', 'cleanup'
    ]);
    stepperService.loadExtensions.and.resolveTo(new Map());
    stepperService.computeExtensionItems.and.returnValue(['ext']);

    TestBed.overrideComponent(TestEditorHostComponent, {
      set: {
        providers: [
          { provide: MappingStepperService, useValue: stepperService },
          { provide: SubstitutionManagementService, useValue: {} },
          { provide: AlertService, useValue: jasmine.createSpyObj('AlertService', ['warning', 'add']) },
          { provide: BottomDrawerService, useValue: {} },
          { provide: BsModalService, useValue: {} },
          provideTranslateStub()
        ]
      }
    });
    host = TestBed.createComponent(TestEditorHostComponent).componentInstance;
  });

  describe('connectorSelectionEmpty', () => {
    it('is true before a deployment entry exists and while no connector is selected', () => {
      expect(host.connectorSelectionEmpty()).toBeTrue();

      host.deploymentMapEntry = { identifier: 'x', connectors: [] };
      expect(host.connectorSelectionEmpty()).toBeTrue();
    });

    it('follows the entry the host holds, whichever way it was set', () => {
      host.deploymentMapEntry = { identifier: 'x', connectors: ['c1'] };
      expect(host.connectorSelectionEmpty()).toBeFalse();

      host.deploymentMapEntryChange({ identifier: 'x', connectors: [] });
      expect(host.connectorSelectionEmpty()).toBeTrue();
    });
  });

  describe('seedMappingCode', () => {
    it('decodes the persisted code once and never overwrites in-progress edits', () => {
      host.mapping.code = stringToBase64('const a = 1;');
      host.seed();
      expect(host.mappingCode).toBe('const a = 1;');

      host.mappingCode = 'edited';
      host.seed();
      expect(host.mappingCode).toBe('edited');
    });

    it('leaves mappingCode undefined for a mapping without code', () => {
      host.seed();
      expect(host.mappingCode).toBeUndefined();
    });
  });

  describe('reloadExtensions', () => {
    it('loads the extensions, refreshes the select items and clears the loading flag', async () => {
      const loaded = new Map([['ext', {} as any]]);
      stepperService.loadExtensions.and.resolveTo(loaded);

      const pending = host.reload();
      expect(host.isLoadingExtensions).toBeTrue();
      await pending;

      expect(host.extensions).toBe(loaded);
      expect(host.extensionItems).toEqual(['ext']);
      expect(host.isLoadingExtensions).toBeFalse();
    });

    it('clears the loading flag when loading fails', async () => {
      stepperService.loadExtensions.and.rejectWith(new Error('boom'));

      await expectAsync(host.reload()).toBeRejectedWithError('boom');

      expect(host.isLoadingExtensions).toBeFalse();
    });
  });

  describe('test mapping', () => {
    it('enterTransformation refreshes validity and publishes a test mapping', () => {
      const published: Mapping[] = [];
      host.updateTestingTemplate.subscribe(m => published.push(m));
      host.currentStepIndex = 3;

      host.enterTransformationStep();

      expect(stepperService.refreshSubstitutionValidity).toHaveBeenCalledWith(host.mapping, host.stepperConfiguration, false);
      expect(published.length).toBe(1);
    });

    it('enterTesting publishes a test mapping without touching validity', () => {
      const published: Mapping[] = [];
      host.updateTestingTemplate.subscribe(m => published.push(m));

      host.enterTestingStep();

      expect(published.length).toBe(1);
      expect(stepperService.refreshSubstitutionValidity).not.toHaveBeenCalled();
    });
  });

  describe('applying changes reported by children', () => {
    it('onSubstitutionsChange writes the list into the mapping it owns', () => {
      const substitutions = [{ pathSource: '$.a', pathTarget: '$.b', repairStrategy: 'DEFAULT', expandArray: false }] as any;

      host.onSubstitutionsChange(substitutions);

      expect(host.mapping.substitutions).toBe(substitutions);
    });

    it('onPropertiesChange applies the patch to the mapping and leaves other fields alone', () => {
      host.mapping.name = 'before';
      host.mapping.filterMapping = 'keep';

      host.onPropertiesChange({ name: 'after', targetAPI: 'EVENT' } as any);

      expect(host.mapping.name).toBe('after');
      expect(host.mapping.targetAPI).toBe('EVENT');
      expect(host.mapping.filterMapping).toBe('keep');
    });

    it('onFilterMappingChange writes the filter expression into the mapping', () => {
      host.onFilterMappingChange('$exists(foo)');

      expect(host.mapping.filterMapping).toBe('$exists(foo)');
    });

    it('onTestingSourceTemplateChanged keeps the host template and the mapping source template in step', () => {
      host.onTestingSourceTemplateChanged({ a: 2 });

      expect(host.sourceTemplate).toEqual({ a: 2 });
      expect(JSON.parse(host.mapping.sourceTemplate)).toEqual({ a: 2 });
    });
  });

  describe('aiButtonLabel', () => {
    it('says refine for an existing draft and generate otherwise', () => {
      host.stepperConfiguration.persistence = 'draft';
      expect(host.aiButtonLabel).toBe('Review / Refine with AI');
      host.stepperConfiguration.persistence = 'create';
      expect(host.aiButtonLabel).toBe('Generate with AI');
    });
  });

  describe('enqueueTransition', () => {
    it('runs transitions one after another', async () => {
      const log: string[] = [];
      let releaseFirst!: () => void;
      const first = host.enqueue(() => new Promise<void>(resolve => {
        log.push('first start');
        releaseFirst = () => { log.push('first end'); resolve(); };
      }));
      const second = host.enqueue(async () => { log.push('second'); });

      await new Promise(resolve => setTimeout(resolve));
      expect(log).toEqual(['first start']);

      releaseFirst();
      await Promise.all([first, second]);
      expect(log).toEqual(['first start', 'first end', 'second']);
    });

    it('keeps running after a transition fails', async () => {
      const failed = host.enqueue(async () => { throw new Error('nope'); });
      await expectAsync(failed).toBeRejected();

      let ran = false;
      await host.enqueue(async () => { ran = true; });
      expect(ran).toBeTrue();
    });
  });
});


/**
 * The connector step reports its selection while the host's view is being checked, after a
 * binding that reads the state was already evaluated (the tab label in the unified editor). A
 * state held in a subject and published from a microtask was needed to avoid
 * ExpressionChangedAfterItHasBeenChecked; a derived signal needs no deferral. These specs run the
 * real change detection, which checks for that error in development mode.
 */
@Component({ selector: 'd11r-test-reporting-child', template: '', standalone: true })
class ReportingChildComponent implements OnInit {
  @Output() reported = new EventEmitter<DeploymentMapEntry>();
  ngOnInit(): void {
    this.reported.emit({ identifier: 'x', connectors: ['c1'] });
  }
}

@Component({
  selector: 'd11r-test-label-host',
  standalone: true,
  imports: [ReportingChildComponent],
  template: `<span id="label">{{ connectorSelectionEmpty() ? 'Connector *' : 'Connector' }}</span>
    <d11r-test-reporting-child (reported)="deploymentMapEntryChange($event)" />`
})
class LabelHostComponent extends TestEditorHostComponent {}

describe('MappingEditorBase connector selection during change detection', () => {
  beforeEach(() => {
    TestBed.overrideComponent(LabelHostComponent, {
      set: {
        providers: [
          {
            provide: MappingStepperService,
            useValue: jasmine.createSpyObj('MappingStepperService', [
              'cleanup', 'registerCompletionProvider', 'loadExtensions', 'computeExtensionItems', 'refreshSubstitutionValidity'
            ])
          },
          { provide: SubstitutionManagementService, useValue: {} },
          { provide: AlertService, useValue: jasmine.createSpyObj('AlertService', ['warning', 'add']) },
          { provide: BottomDrawerService, useValue: {} },
          { provide: BsModalService, useValue: {} },
          provideTranslateStub()
        ]
      }
    });
  });

  it('shows the reported selection without ExpressionChangedAfterItHasBeenChecked', () => {
    const fixture = TestBed.createComponent(LabelHostComponent);

    expect(() => fixture.detectChanges()).not.toThrow();

    expect(fixture.nativeElement.querySelector('#label').textContent).toBe('Connector');
  });
});
