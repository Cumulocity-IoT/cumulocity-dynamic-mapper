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
import { AlertService } from '@c8y/ngx-components';
import { TranslateService } from '@ngx-translate/core';
import { BsModalService } from 'ngx-bootstrap/modal';
import { of, ReplaySubject } from 'rxjs';
import { MappingStepTestingComponent } from './mapping-step-testing.component';
import { TestingService } from '../core/testing.service';
import { Direction, Mapping, MappingType, Qos, StepperConfiguration, TransformationType } from '../../shared';
import { DynamicMapperRequest, TestContext, TestResult } from '../../shared/mapping/processor/processor.model';
import { EditorMode } from '../../shared/mapping/stepper.model';

/**
 * Unit tests for the "device does not exist" confirmation in {@link MappingStepTestingComponent}.
 *
 * The backend reports a missing device as a warning (flow / extension mappings, no request is
 * produced) or as an error (substitution mappings), always carrying the marker
 * "createNonExistingDevice is disabled". The dialog must open in both cases, and its wording must
 * make clear that a real send creates a real device while a dry run only assumes one.
 */
describe('MappingStepTestingComponent – device does not exist (Dry Run)', () => {
  const MARKER = 'Device with externalId \'x\' (type \'c8y_Serial\') not found in inventory and createNonExistingDevice is disabled';

  let component: MappingStepTestingComponent;
  let fixture: ComponentFixture<MappingStepTestingComponent>;
  let testingService: jasmine.SpyObj<TestingService>;
  let alertService: jasmine.SpyObj<AlertService>;
  let modalService: jasmine.SpyObj<BsModalService>;
  let answer: boolean;

  const buildMapping = (): Mapping => ({
    id: '1',
    identifier: 'm',
    name: 'm',
    direction: Direction.INBOUND,
    targetAPI: 'MEASUREMENT',
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    substitutions: [],
    sourceTemplate: '{"a":1}',
    targetTemplate: '{}',
    mappingTopic: 'test/topic',
    mappingTopicSample: 'test/topic/sample',
    active: true,
    debug: false,
    tested: false,
    filterMapping: '',
    createNonExistingDevice: false,
    updateExistingDevice: false,
    useExternalId: true,
    externalIdType: 'c8y_Serial',
    supportsMessageContext: false,
    qos: Qos.AT_MOST_ONCE,
    lastUpdate: Date.now()
  });

  const notFoundAsWarning: TestResult = { success: true, requests: [], errors: [], warnings: [MARKER] };
  const notFoundAsError: TestResult = { success: false, requests: [], errors: [MARKER] };
  const ok: TestResult = { success: true, requests: [], errors: [], warnings: [] };

  /** The modal state the component passed to the n-th showConfirmation call. */
  const shownModal = (n = 0): { title: string; message: string; labels: { ok: string; cancel: string } } =>
    (modalService.show.calls.argsFor(n)[1] as any).initialState;

  beforeEach(async () => {
    answer = true;
    testingService = jasmine.createSpyObj('TestingService', ['testMapping', 'resetMockCache']);
    testingService.resetMockCache.and.resolveTo();
    alertService = jasmine.createSpyObj('AlertService', ['add', 'danger']);
    modalService = jasmine.createSpyObj('BsModalService', ['show']);
    modalService.show.and.callFake(() => ({ content: { closeSubject: of(answer) } }) as any);

    TestBed.overrideComponent(MappingStepTestingComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
    await TestBed.configureTestingModule({
      imports: [MappingStepTestingComponent],
      providers: [
        { provide: TestingService, useValue: testingService },
        { provide: AlertService, useValue: alertService },
        { provide: BsModalService, useValue: modalService },
        // The app's missing-translation handler returns the key with its {{placeholders}} filled; do the same.
        {
          provide: TranslateService,
          useValue: {
            instant: (key: string, params?: Record<string, unknown>) =>
              key.replace(/\{\{\s*(\w+)\s*\}\}/g, (_m: string, name: string) => String(params?.[name] ?? ''))
          }
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingStepTestingComponent);
    component = fixture.componentInstance;
    component.mapping = buildMapping();
    component.stepperConfiguration = { editorMode: EditorMode.CREATE, direction: Direction.INBOUND } as StepperConfiguration;
    component.updateTestingTemplate = new ReplaySubject<Mapping>(1);
    await component.ngOnInit();
  });

  it('opens the dialog for a missing device reported as a WARNING (Dry Run) and offers to assume it exists', async () => {
    testingService.testMapping.and.returnValues(Promise.resolve(notFoundAsWarning), Promise.resolve(ok));

    await component.onTestTransformation();

    expect(modalService.show).toHaveBeenCalledTimes(1);
    expect(shownModal().message).toContain('assume for this test that the device exists');
    expect(shownModal().labels.ok).toBe('Assume device exists');
  });

  it('opens the dialog for a missing device reported as an ERROR (substitution mappings)', async () => {
    testingService.testMapping.and.returnValues(Promise.resolve(notFoundAsError), Promise.resolve(ok));

    await component.onTestTransformation();

    expect(modalService.show).toHaveBeenCalledTimes(1);
    expect(shownModal().labels.ok).toBe('Assume device exists');
  });

  it('on confirm, re-runs the test with createNonExistingDevice = true', async () => {
    testingService.testMapping.and.returnValues(Promise.resolve(notFoundAsError), Promise.resolve(ok));

    await component.onTestTransformation();

    expect(testingService.testMapping).toHaveBeenCalledTimes(2);
    const first = testingService.testMapping.calls.argsFor(0)[0] as TestContext;
    const second = testingService.testMapping.calls.argsFor(1)[0] as TestContext;
    expect(first.send).toBeFalse();
    expect(second.send).toBeFalse();
    expect(second.mapping.createNonExistingDevice).toBeTrue();
  });

  it('a Live Test never offers to create a real device, it just reports the failure', async () => {
    testingService.testMapping.and.returnValue(Promise.resolve(notFoundAsError));

    await component.onSendTest();

    expect(modalService.show).not.toHaveBeenCalled();
    expect(testingService.testMapping).toHaveBeenCalledTimes(1);
    expect(component.testMapping.createNonExistingDevice).toBeFalse();
    expect(component.testingModel.logs).toContain(`ERROR: ${MARKER}`);
  });

  it('does not send a createTestDevice flag any more: a Live Test always uses a test device', async () => {
    testingService.testMapping.and.returnValue(Promise.resolve(ok));

    await component.onSendTest();

    const sent = testingService.testMapping.calls.argsFor(0)[0] as TestContext;
    expect(sent.send).toBeTrue();
    expect('createTestDevice' in sent).toBeFalse();
  });

  it('on cancel, does not re-run and keeps the error visible in the console', async () => {
    answer = false;
    testingService.testMapping.and.returnValue(Promise.resolve(notFoundAsError));

    await component.onTestTransformation();

    expect(testingService.testMapping).toHaveBeenCalledTimes(1);
    expect(component.testMapping.createNonExistingDevice).toBeFalse();
    expect(component.testingModel.logs).toContain(`ERROR: ${MARKER}`);
  });

  it('does not open the dialog when createNonExistingDevice is already enabled (it could not fix the failure)', async () => {
    component.testMapping.createNonExistingDevice = true;
    testingService.testMapping.and.returnValue(Promise.resolve(notFoundAsError));

    await component.onTestTransformation();

    expect(modalService.show).not.toHaveBeenCalled();
    expect(testingService.testMapping).toHaveBeenCalledTimes(1);
    expect(component.testingModel.logs).toContain(`ERROR: ${MARKER}`);
  });

  it('does not open the dialog when the result does not mention the marker', async () => {
    testingService.testMapping.and.returnValue(
      Promise.resolve({ success: false, requests: [], errors: ['some other failure'] } as TestResult));

    await component.onTestTransformation();

    expect(modalService.show).not.toHaveBeenCalled();
    expect(testingService.testMapping).toHaveBeenCalledTimes(1);
    expect(component.testingModel.logs).toContain('ERROR: some other failure');
  });

  it('does not open the dialog for a clean result', async () => {
    testingService.testMapping.and.returnValue(Promise.resolve(ok));

    await component.onTestTransformation();

    expect(modalService.show).not.toHaveBeenCalled();
  });

  describe('describeSendResult', () => {
    const sent = (req: Partial<DynamicMapperRequest>, extra: Partial<TestResult> = {}): TestResult => ({
      success: true, errors: [], requests: [req as DynamicMapperRequest], ...extra
    });

    it('names the created alarm and the device it belongs to', () => {
      const text = component.describeSendResult(sent({
        api: 'ALARM', sourceId: '491713288', externalIdType: 'c8y_Serial', externalId: '862524060507262',
        response: '{"id":"1684457"}'
      }));
      expect(text).toBe('Live test successful: created alarm 1684457 on device 491713288 (c8y_Serial 862524060507262).');
    });

    it('describes an inventory send as the device itself', () => {
      const text = component.describeSendResult(sent({
        api: 'INVENTORY', externalIdType: 'c8y_Serial', externalId: '862', response: { id: '491713288' }
      }));
      expect(text).toBe('Live test successful: device 491713288 (c8y_Serial 862) created or updated.');
    });

    it('still reads well when the response carries no id', () => {
      const text = component.describeSendResult(sent({ api: 'MEASUREMENT', sourceId: '42' }));
      expect(text).toBe('Live test successful: created measurement on device 42.');
    });

    it('mentions further requests and the test device', () => {
      const result = sent({ api: 'EVENT', sourceId: '7', response: { id: '9' } },
        { testDeviceId: '7', testDeviceCreated: true, testDeviceExternalId: 'd11r_liveTest_abc' });
      result.requests.push({ api: 'EVENT' } as DynamicMapperRequest);
      const text = component.describeSendResult(result);
      expect(text).toContain('created event 9 on device 7');
      expect(text).toContain('1 more request sent');
      expect(text).toContain('Test device 7 (d11r_liveTest_abc) created; remove it under Monitoring > Test device.');
    });

    it('says a test device was reused from an earlier Live Test, with its prefixed external ID', () => {
      const text = component.describeSendResult(sent(
        { api: 'ALARM', sourceId: '491713288', externalIdType: 'c8y_Serial', response: { id: '1' } },
        { testDeviceId: '491713288', testDeviceCreated: false, testDeviceExternalId: 'd11r_liveTest_862' }));
      expect(text).toContain('Reused test device 491713288 (c8y_Serial d11r_liveTest_862) from an earlier Live Test.');
    });
  });

  describe('the "assume device exists" choice is per run', () => {
    it('is not written into testMapping, so it does not stick', async () => {
      testingService.testMapping.and.returnValues(Promise.resolve(notFoundAsError), Promise.resolve(ok));

      await component.onTestTransformation();

      expect(component.testMapping.createNonExistingDevice).toBeFalse();
    });

    it('asks again on the next Dry Run, and a Live Test never carries it', async () => {
      testingService.testMapping.and.returnValues(
        Promise.resolve(notFoundAsError), Promise.resolve(ok), // first Dry Run: asked, confirmed, re-run
        Promise.resolve(notFoundAsError), Promise.resolve(ok), // second Dry Run: asked again
        Promise.resolve(ok)); // Live Test

      await component.onTestTransformation();
      await component.onResetTransformation();
      await component.onTestTransformation();
      expect(modalService.show).toHaveBeenCalledTimes(2);

      await component.onSendTest();
      const live = testingService.testMapping.calls.mostRecent().args[0] as TestContext;
      expect(live.send).toBeTrue();
      expect(live.mapping.createNonExistingDevice).toBeFalse();
    });
  });

  describe('Live Test availability', () => {
    beforeEach(() => {
      component.stepperConfiguration.allowTestSending = true;
      component.testMapping.useExternalId = true;
    });

    it('explains that a Dry Run result is needed first', () => {
      expect(component.liveTestDisabledReason).toContain('Run a Dry Run first');
      expect(component.disableTestSending()).toBeTrue();
    });

    it('is available once a Dry Run produced a result', () => {
      component.testingModel.results = [{ api: 'ALARM' } as DynamicMapperRequest];

      expect(component.liveTestDisabledReason).toBeUndefined();
      expect(component.disableTestSending()).toBeFalse();
    });

    it('explains that the mapping must use an external ID', () => {
      component.testMapping.useExternalId = false;
      component.testingModel.results = [{ api: 'ALARM' } as DynamicMapperRequest];

      expect(component.liveTestDisabledReason).toContain('external ID');
    });

    it('explains when sending is not allowed for the payload/transformation combination', () => {
      component.stepperConfiguration.allowTestSending = false;

      expect(component.liveTestDisabledReason).toContain('not available');
    });

    it('tells the user how to get the Dry Run button back', () => {
      expect(component.dryRunTitle).toContain('without writing anything');
      component.testingModel.results = [{ api: 'ALARM' } as DynamicMapperRequest];
      expect(component.dryRunTitle).toContain('Reset Dry Run');
    });
  });

  describe('running state', () => {
    it('marks which button is running and clears it afterwards', async () => {
      let release!: (r: TestResult) => void;
      testingService.testMapping.and.returnValue(new Promise<TestResult>(resolve => (release = resolve)));

      const running = component.onSendTest();
      expect(component.isLoading).toBeTrue();
      expect(component.runningTest).toBe('liveTest');

      release(ok);
      await running;
      expect(component.isLoading).toBeFalse();
      expect(component.runningTest).toBeUndefined();
    });

    it('reports a failing request as an alert instead of opening a dialog', async () => {
      testingService.testMapping.and.rejectWith(new Error('boom'));

      await component.onTestTransformation();

      expect(alertService.danger).toHaveBeenCalledWith('Test execution failed: boom');
      expect(modalService.show).not.toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();
    });
  });

  describe('help texts', () => {
    it('describe the broker for an outbound mapping and the test device for an inbound one', () => {
      expect(component.testHelpText).toContain('test device');
      expect(component.liveTestTitle).toContain('test device');

      component.mapping.direction = Direction.OUTBOUND;
      expect(component.testHelpText).toContain('connected broker');
      expect(component.testHelpText).not.toContain('d11r_liveTest_');
      expect(component.liveTestTitle).toContain('broker');
    });
  });

  describe('behaviour around the buttons', () => {
    it('shows an explanation instead of dead buttons when neither test is permitted', () => {
      component.stepperConfiguration.allowTestTransformation = false;
      component.stepperConfiguration.allowTestSending = false;
      expect(component.testingUnavailable).toBeTrue();

      component.stepperConfiguration.allowTestTransformation = true;
      expect(component.testingUnavailable).toBeFalse();
    });

    it('steps through the results, skips hidden ones and wraps around', () => {
      component.testingModel.results = [
        { api: 'ALARM', request: {} },
        { api: 'ALARM', request: {}, hidden: true },
        { api: 'ALARM', request: {} }
      ] as DynamicMapperRequest[];
      component.selectedResult$.next(0);

      component.onNextTestResult();
      expect(component.selectedResult$.getValue()).toBe(2); // 1 is hidden

      component.onNextTestResult();
      expect(component.selectedResult$.getValue()).toBe(0); // wrapped
    });

    it('Reset Dry Run clears the results and the mock, so the Dry Run is available again', async () => {
      testingService.testMapping.and.returnValue(Promise.resolve({
        ...ok, requests: [{ api: 'ALARM', request: '{}' } as DynamicMapperRequest]
      }));
      await component.onTestTransformation();
      expect(component.testingModel.results.length).toBe(1);
      testingService.resetMockCache.calls.reset();

      await component.onResetTransformation();

      expect(component.testingModel.results).toEqual([]);
      expect(testingService.resetMockCache).toHaveBeenCalledTimes(1);
    });

    it('opening the step reports a failing mock reset instead of failing silently', async () => {
      testingService.resetMockCache.and.rejectWith(new Error('no connection'));

      await component.ngOnInit();

      expect(alertService.danger).toHaveBeenCalledWith('Failed to clear cache: no connection');
    });

    it('a successful Live Test raises the alert that says what was created', async () => {
      testingService.testMapping.and.returnValue(Promise.resolve({
        success: true, errors: [], warnings: [],
        requests: [{ api: 'ALARM', sourceId: '4711', response: '{"id":"99"}' } as DynamicMapperRequest]
      }));

      await component.onSendTest();

      expect(alertService.add).toHaveBeenCalledTimes(1);
      const alert = alertService.add.calls.mostRecent().args[0] as { text: string; type: string };
      expect(alert.type).toBe('info');
      expect(alert.text).toContain('created alarm 99 on device 4711');
    });

    it('a Dry Run raises no success alert', async () => {
      testingService.testMapping.and.returnValue(Promise.resolve(ok));

      await component.onTestTransformation();

      expect(alertService.add).not.toHaveBeenCalled();
    });

    it('counts errors and warnings for the console header, whatever the prefix style', () => {
      component.testingModel.logs = ['ERROR: a', 'JS ERROR: b', 'WARNING: c', 'INFO d', 'LOG e'];

      expect(component.logCounts.errors).toBe(2);
      expect(component.logCounts.warnings).toBe(1);
    });
  });

  describe('translation', () => {
    it('sends every user-facing message through the TranslateService, with its placeholders as parameters', () => {
      const translate = TestBed.inject(TranslateService);
      const instant = spyOn(translate, 'instant').and.callFake((key: string) => `<${key}>`);

      const text = component.describeSendResult({
        success: true, errors: [], warnings: [],
        requests: [{ api: 'ALARM', sourceId: '4711', response: { id: '99' } } as DynamicMapperRequest]
      });

      expect(text).toBe('<Live test successful: created {{noun}} {{id}} on device {{deviceId}}{{externalId}}.>');
      expect(instant).toHaveBeenCalledWith(
        'Live test successful: created {{noun}} {{id}} on device {{deviceId}}{{externalId}}.',
        jasmine.objectContaining({ noun: '<alarm>', id: '99', deviceId: '4711' }));
    });

    it('translates the title and text of an error alert but keeps the technical reason as is', async () => {
      const translate = TestBed.inject(TranslateService);
      spyOn(translate, 'instant').and.callFake((key: string) => `<${key}>`);
      testingService.testMapping.and.rejectWith(new Error('boom'));

      await component.onTestTransformation();

      expect(alertService.danger).toHaveBeenCalledWith('<Test execution failed>: boom');
    });
  });
});
