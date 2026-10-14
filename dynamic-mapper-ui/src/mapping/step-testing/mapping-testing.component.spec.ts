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
import { BsModalService } from 'ngx-bootstrap/modal';
import { of, ReplaySubject } from 'rxjs';
import { MappingStepTestingComponent } from './mapping-testing.component';
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
        { provide: BsModalService, useValue: modalService }
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
});
