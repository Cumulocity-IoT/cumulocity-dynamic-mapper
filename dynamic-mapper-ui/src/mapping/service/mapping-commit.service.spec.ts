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
import { FormControl, FormGroup } from '@angular/forms';
import { AlertService } from '@c8y/ngx-components';
import {
  CommitBlocker,
  CommitMappingRequest,
  CommitResult,
  commitSuccessMessage,
  MappingCommitService,
  snapshotConnectors
} from './mapping-commit.service';
import { MappingService } from '../core/mapping.service';
import {
  ALERT_ACTION_REQUIRED_TIMEOUT,
  ALERT_SUCCESS_TIMEOUT,
  DeploymentMapEntry,
  Direction,
  Mapping,
  MappingType,
  StepperConfiguration,
  TransformationType
} from '../../shared';
import { captureMappingContentSnapshot } from '../../shared/mapping/util';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';

/** Unit tests for the save path shared by the stepper's parent and the unified editor. */
describe('MappingCommitService', () => {
  let service: MappingCommitService;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockMappingService: jasmine.SpyObj<MappingService>;

  function makeMapping(overrides: Partial<Mapping> = {}): Mapping {
    return {
      id: 'test-id',
      identifier: 'test-identifier',
      name: 'Test',
      direction: Direction.INBOUND,
      targetAPI: 'MEASUREMENT',
      mappingType: MappingType.JSON,
      transformationType: TransformationType.DEFAULT,
      substitutions: [],
      sourceTemplate: '{}',
      targetTemplate: '{}',
      mappingTopic: 'test/topic',
      mappingTopicSample: 'test/topic',
      active: true,
      debug: false,
      tested: false,
      filterMapping: '',
      createNonExistingDevice: false,
      updateExistingDevice: false,
      useExternalId: false,
      externalIdType: '',
      qos: undefined,
      ...overrides
    } as Mapping;
  }

  function makeStepperConfig(overrides: Partial<StepperConfiguration> = {}): StepperConfiguration {
    return {
      persistence: 'draft',
      direction: Direction.INBOUND,
      showEditorSource: true,
      showEditorTarget: true,
      allowDefiningSubstitutions: true,
      allowTestSending: true,
      allowTestTransformation: true,
      allowTemplateExpansion: false,
      allowNoDefinedIdentifier: false,
      showCodeEditor: false,
      ...overrides
    } as StepperConfiguration;
  }

  beforeEach(() => {
    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', [
      'add',
      'remove',
      'success',
      'danger'
    ], { state: [] });
    mockMappingService = jasmine.createSpyObj<MappingService>('MappingService', [
      'saveDraft',
      'createMapping',
      'updateDefinedDeploymentMapEntry',
      'refreshMappings'
    ]);
    mockMappingService.saveDraft.and.resolveTo(undefined as any);
    mockMappingService.createMapping.and.resolveTo(undefined as any);
    mockMappingService.updateDefinedDeploymentMapEntry.and.resolveTo(undefined as any);

    TestBed.configureTestingModule({
      providers: [
        MappingCommitService,
        { provide: MappingService, useValue: mockMappingService },
        { provide: AlertService, useValue: mockAlertService }
      ]
    });

    service = TestBed.inject(MappingCommitService);
  });


  // -------------------------------------------------------------------------
  // encodeMappingForCommit
  // Phase 5 of docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md — the
  // consolidated onCommitButton() encoding block previously duplicated across
  // MappingStepperComponent/MappingUnifiedEditorComponent.
  // -------------------------------------------------------------------------

  describe('encodeMappingForCommit', () => {
    it('JSON-stringifies source/target templates when template expansion is disabled', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(
        mapping, { a: 1 }, { b: 2 }, undefined, undefined, false, 'create'
      );
      expect('error' in result).toBe(false);
      expect(mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
      expect(mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
    });

    it('reduces (compacts) source/target templates when template expansion is allowed', () => {
      const mapping = makeMapping();
      // reduceSourceTemplate deletes the expansion tokens at the TOP level of the template,
      // so the fixture has to put them there — nested occurrences are left alone by design.
      service.encodeMappingForCommit(
        mapping, { a: 1, _TOPIC_LEVEL_: ['x'], _IDENTITY_: {} }, { b: 2 }, undefined, undefined, true, 'create'
      );
      expect(mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
      expect(mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
    });

    it('encodes mappingCode to base64 (stripped of metadata tags) when provided', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(
        mapping, {}, {}, 'function onMessage() {}', undefined, false, 'create'
      );
      expect('error' in result).toBe(false);
      expect(mapping.code).toBeTruthy();
      expect(atob(mapping.code!)).toContain('function onMessage');
    });

    it('leaves mapping.code untouched when no mappingCode is provided', () => {
      const mapping = makeMapping({ code: undefined });
      service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, 'create');
      expect(mapping.code).toBeUndefined();
    });

    it('always reports contentChanged=true for CREATE (no snapshot needed)', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, 'create');
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('detects no content change in UPDATE mode when nothing differs from the snapshot', () => {
      const mapping = makeMapping();
      const snapshot = captureMappingContentSnapshot(mapping, {}, {}, undefined);
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, snapshot, false, 'draft');
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(false);
    });

    it('detects a content change in UPDATE mode when the source template differs from the snapshot', () => {
      const mapping = makeMapping();
      const snapshot = captureMappingContentSnapshot(mapping, { original: true }, {}, undefined);
      const result = service.encodeMappingForCommit(mapping, { changed: true }, {}, undefined, snapshot, false, 'draft');
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('treats UPDATE mode with no snapshot as always changed (defensive fallback)', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, 'draft');
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('returns an error for a substitutions-as-code mapping with no code', () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, 'create');
      expect('error' in result).toBe(true);
      expect((result as { error: string }).error).toContain('Internal error');
    });

    it('succeeds for a substitutions-as-code mapping that does have code', () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const result = service.encodeMappingForCommit(mapping, {}, {}, 'function onMessage() {}', undefined, false, 'create');
      expect('error' in result).toBe(false);
    });
  });

  /**
   * The CommitResult matrix for `commitMapping()` — the single commit path shared by the unified
   * editor and the stepper's parent (docs/planning/IMPLEMENTATION-PLAN-COMMIT-MAPPING.md).
   *
   * These exist so the call sites can be moved onto it safely: the save path touches drafts,
   * deployments and an optimistic-concurrency token, and has never had an executed regression run.
   */
  describe('commitMapping', () => {
    const CONNECTORS = ['connector-a'];

    function makeDeployment(connectors: string[] = CONNECTORS): DeploymentMapEntry {
      return { identifier: 'test-identifier', connectors } as DeploymentMapEntry;
    }

    /** An UPDATE commit of an unmodified mapping: no content change, no connector change. */
    function makeRequest(overrides: Partial<CommitMappingRequest> = {}): CommitMappingRequest {
      const mapping = overrides.mapping ?? makeMapping();
      const deploymentMapEntry = overrides.deploymentMapEntry ?? makeDeployment();
      return {
        mapping,
        deploymentMapEntry,
        initialDeploymentConnectors: snapshotConnectors(deploymentMapEntry),
        stepperConfiguration: makeStepperConfig(),
        sourceTemplate: {},
        targetTemplate: {},
        mappingCode: undefined,
        initialContentSnapshot: captureMappingContentSnapshot(mapping, {}, {}, undefined),
        ...overrides
      };
    }

    function expectSaved(result: CommitResult): Extract<CommitResult, { status: 'saved' }> {
      expect(result.status).toBe('saved');
      return result as Extract<CommitResult, { status: 'saved' }>;
    }

    describe('preconditions block before anything is written', () => {
      it('blocks on NO_CONNECTOR when the deployment selects none', async () => {
        const result = await service.commitMapping(makeRequest({ deploymentMapEntry: makeDeployment([]) }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.NO_CONNECTOR }));
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('blocks on MAPPING_TOPIC for INBOUND with a blank topic, and marks the control', async () => {
        const propertyFormly = new FormGroup({ mappingTopic: new FormControl('') });
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ mappingTopic: '   ' }),
          forms: { propertyFormly, templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.MAPPING_TOPIC }));
        expect(propertyFormly.get('mappingTopic')?.errors).toEqual({ required: true });
        expect(propertyFormly.get('mappingTopic')?.touched).toBe(true);
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
      });

      it('does not apply the topic check to OUTBOUND', async () => {
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ mappingTopic: '', direction: Direction.OUTBOUND }),
          stepperConfiguration: makeStepperConfig({ direction: Direction.OUTBOUND }),
          forms: { propertyFormly: new FormGroup({}), templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result.status).toBe('saved');
      });

      it('blocks on PROPERTY_FORM when the general-settings form is invalid', async () => {
        const propertyFormly = new FormGroup({ other: new FormControl('', () => ({ bad: true })) });
        const result = await service.commitMapping(makeRequest({
          forms: { propertyFormly, templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.PROPERTY_FORM }));
        expect(propertyFormly.get('other')?.touched).toBe(true);
      });

      it('blocks on EXTENSION_SELECTION only when the selectors are rendered', async () => {
        const templateForm = new FormGroup({
          extensionName: new FormControl('', () => ({ required: true })),
          eventName: new FormControl('')
        });

        const hidden = await service.commitMapping(makeRequest({
          forms: { propertyFormly: new FormGroup({}), templateForm, validateExtensionSelection: false }
        }));
        expect(hidden.status).toBe('saved');

        const shown = await service.commitMapping(makeRequest({
          forms: { propertyFormly: new FormGroup({}), templateForm, validateExtensionSelection: true }
        }));
        expect(shown).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.EXTENSION_SELECTION }));
        expect(templateForm.get('extensionName')?.touched).toBe(true);
        expect(templateForm.get('eventName')?.touched).toBe(true);
      });

      it('skips every form-backed precondition when the caller passes no forms (the stepper)', async () => {
        // cdk-stepper already enforces linear completion, so an INBOUND mapping with no topic
        // cannot be reached there — and must not be blocked here either.
        const result = await service.commitMapping(makeRequest({ mapping: makeMapping({ mappingTopic: '' }) }));

        expect(result.status).toBe('saved');
      });

      it('blocks on ENCODING when the mapping is substitutions-as-code without code', async () => {
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ transformationType: TransformationType.SMART_FUNCTION }),
          mappingCode: undefined
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.ENCODING }));
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
      });
    });

    describe('save modes', () => {
      it('saves a draft on UPDATE when the content changed', async () => {
        const mapping = makeMapping();
        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(mockMappingService.saveDraft).toHaveBeenCalledWith(mapping.id, mapping);
        expect(mockMappingService.createMapping).not.toHaveBeenCalled();
        expect(expectSaved(result).contentChanged).toBe(true);
      });

      it('does not save a draft on UPDATE for a connector-only change', async () => {
        const deploymentMapEntry = makeDeployment(['connector-b']);
        const result = await service.commitMapping(makeRequest({
          deploymentMapEntry,
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalledWith(deploymentMapEntry);
        const saved = expectSaved(result);
        expect(saved.contentChanged).toBe(false);
        expect(saved.deploymentChanged).toBe(true);
      });

      it('creates the mapping on CREATE, and always treats it as a content change', async () => {
        const mapping = makeMapping();
        const result = await service.commitMapping(makeRequest({
          mapping,
          stepperConfiguration: makeStepperConfig({ persistence: 'create' })
        }));

        expect(mockMappingService.createMapping).toHaveBeenCalledWith(mapping);
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(expectSaved(result).contentChanged).toBe(true);
      });

      it('creates the mapping on COPY', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ persistence: 'create', isCopy: true }) }));

        expect(mockMappingService.createMapping).toHaveBeenCalled();
      });

      it('returns the server copy as `persisted`, so a caller that stays open can re-baseline', async () => {
        const mapping = makeMapping({ lastUpdate: 1000 } as any);
        const serverCopy = makeMapping({ lastUpdate: 2000 } as any);
        mockMappingService.saveDraft.and.resolveTo(serverCopy);

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(expectSaved(result).persisted).toBe(serverCopy);
      });

      it('falls back to the request mapping when nothing was written', async () => {
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({ mapping }));

        // Connector-only/no-op update: no save call, so there is no server copy to adopt.
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(expectSaved(result).persisted).toBe(mapping);
      });

      it('refreshes the grid for the mapping direction', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ direction: Direction.OUTBOUND }) }));

        expect(mockMappingService.refreshMappings).toHaveBeenCalledWith(Direction.OUTBOUND);
      });
    });

    describe('deployment persistence', () => {
      it('writes the deployment when the connectors changed', async () => {
        const deploymentMapEntry = makeDeployment(['connector-b']);
        await service.commitMapping(makeRequest({
          deploymentMapEntry,
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalledWith(deploymentMapEntry);
      });

      it('writes the deployment on CREATE even though the connectors are unchanged', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ persistence: 'create' }) }));

        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalled();
      });

      it('skips the redundant write on UPDATE when the connectors are unchanged', async () => {
        // The backend PUT reconciles subscriptions across all connectors live, so a no-op write
        // is not free. This is the reconciliation recorded in §4 of the plan: the stepper parent
        // used to write unconditionally here.
        await service.commitMapping(makeRequest());

        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('does not write the deployment when the mapping itself failed to save', async () => {
        mockMappingService.createMapping.and.rejectWith(new Error('boom'));

        const result = await service.commitMapping(makeRequest({
          stepperConfiguration: makeStepperConfig({ persistence: 'create' })
        }));

        expect(result.status).toBe('failed');
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('still reports saved when only the deployment write fails, but alerts the user', async () => {
        mockMappingService.updateDefinedDeploymentMapEntry.and.rejectWith(new Error('no such connector'));

        const result = await service.commitMapping(makeRequest({
          deploymentMapEntry: makeDeployment(['connector-b']),
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(result.status).toBe('saved');
        expect(mockAlertService.danger).toHaveBeenCalledWith(jasmine.stringContaining('no such connector'));
      });
    });

    describe('failures', () => {
      it('reports rejected, without an alert, when the server rejects the draft', async () => {
        const error = new MappingValidationError('invalid', ['e1'], []);
        mockMappingService.saveDraft.and.rejectWith(error);
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(result).toEqual({ status: 'rejected', error });
        // The caller lists the problems in a drawer instead; a toast can only be dismissed.
        expect(mockAlertService.danger).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('reports rejected when the server rejects a create', async () => {
        const error = new MappingValidationError('invalid', ['e1'], []);
        mockMappingService.createMapping.and.rejectWith(error);

        const result = await service.commitMapping(makeRequest({
          stepperConfiguration: makeStepperConfig({ persistence: 'create' })
        }));

        expect(result).toEqual({ status: 'rejected', error });
      });

      it('reports failed and alerts once for any other save error', async () => {
        mockMappingService.saveDraft.and.rejectWith(new Error('network down'));
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(result.status).toBe('failed');
        expect(mockAlertService.danger).toHaveBeenCalledTimes(1);
        expect(mockAlertService.danger).toHaveBeenCalledWith(jasmine.stringContaining('network down'));
      });

      it('still refreshes the grid after a failed save', async () => {
        mockMappingService.createMapping.and.rejectWith(new Error('boom'));

        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ persistence: 'create' }) }));

        expect(mockMappingService.refreshMappings).toHaveBeenCalled();
      });
    });

    /**
     * `lastUpdate` is the optimistic-concurrency token: MappingVersionService.saveDraft rejects a
     * draft whose lastUpdate differs from the stored snapshot's. The client must echo back what it
     * received — stamping Date.now() here would make every concurrent-edit check pass vacuously.
     */
    it('never stamps lastUpdate', async () => {
      const mapping = makeMapping({ lastUpdate: 1234567890 } as any);
      await service.commitMapping(makeRequest({
        mapping,
        initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
      }));

      expect(mockMappingService.saveDraft).toHaveBeenCalled();
      expect(mockMappingService.saveDraft.calls.mostRecent().args[1].lastUpdate).toBe(1234567890);
    });
  });

  describe('commitSuccessMessage', () => {
    const saved = (contentChanged: boolean, deploymentChanged: boolean) =>
      ({ status: 'saved', contentChanged, deploymentChanged }) as Extract<CommitResult, { status: 'saved' }>;

    it('announces a creation for every non-UPDATE mode', () => {
      expect(commitSuccessMessage(saved(true, true), 'M', 'create')?.text).toContain('created successfully');
      expect(commitSuccessMessage(saved(true, true), 'M', 'create')?.text).toContain('created successfully');
    });

    it('distinguishes the three UPDATE outcomes', () => {
      expect(commitSuccessMessage(saved(true, true), 'M', 'draft')?.text)
        .toContain('Saved draft and connector assignments');
      expect(commitSuccessMessage(saved(true, false), 'M', 'draft')?.text)
        .toContain('Saved draft for M');
      expect(commitSuccessMessage(saved(false, true), 'M', 'draft')?.text)
        .toContain('Connector assignments for M saved');
    });

    it('does not claim connector assignments saved when the deployment write failed', () => {
      const failed = (contentChanged: boolean, deploymentChanged: boolean) =>
        ({ status: 'saved', contentChanged, deploymentChanged, deploymentFailed: true }) as
          Extract<CommitResult, { status: 'saved' }>;

      expect(commitSuccessMessage(failed(false, true), 'M', 'draft')).toBeUndefined();
      expect(commitSuccessMessage(failed(true, true), 'M', 'draft')?.text).toContain('Saved draft for M');
    });

    it('keeps the draft messages up longer, because they ask for a follow-up action', () => {
      // Cumulocity auto-dismisses a detail-less success alert after 3s; these two tell the user
      // to publish and activate afterwards, so they must outlast that.
      expect(commitSuccessMessage(saved(true, true), 'M', 'draft')?.timeout)
        .toBe(ALERT_ACTION_REQUIRED_TIMEOUT);
      expect(commitSuccessMessage(saved(true, false), 'M', 'draft')?.timeout)
        .toBe(ALERT_ACTION_REQUIRED_TIMEOUT);
      expect(ALERT_ACTION_REQUIRED_TIMEOUT).toBeGreaterThan(3000);
    });

    it('uses the ordinary success dwell time for plain confirmations', () => {
      expect(commitSuccessMessage(saved(true, true), 'M', 'create')?.timeout)
        .toBe(ALERT_SUCCESS_TIMEOUT);
      expect(commitSuccessMessage(saved(false, true), 'M', 'draft')?.timeout)
        .toBe(ALERT_SUCCESS_TIMEOUT);
    });

    it('says nothing when an UPDATE changed nothing', () => {
      expect(commitSuccessMessage(saved(false, false), 'M', 'draft')).toBeUndefined();
    });
  });

});
