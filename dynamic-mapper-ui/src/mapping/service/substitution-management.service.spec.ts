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
import { BsModalService } from 'ngx-bootstrap/modal';
import { SubstitutionManagementService } from './substitution-management.service';
import {
  Direction,
  Mapping,
  MappingType,
  RepairStrategy,
  StepperConfiguration,
  Substitution,
  TransformationType
} from '../../shared';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeMapping(substitutions: Substitution[] = []): Mapping {
  return {
    id: '1',
    identifier: 'test-mapping',
    name: 'Test',
    direction: Direction.INBOUND,
    targetAPI: 'MEASUREMENT',
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    substitutions,
    sourceTemplate: '{}',
    targetTemplate: '{}',
    mappingTopic: 'a/b',
    mappingTopicSample: 'a/b',
    active: true,
    debug: false,
    tested: false,
    filterMapping: '',
    createNonExistingDevice: false,
    updateExistingDevice: false,
    useExternalId: false,
    externalIdType: '',
    qos: undefined
  } as Mapping;
}

function makeSubstitution(pathSource = '$.temp', pathTarget = '$.value'): Substitution {
  return {
    pathSource,
    pathTarget,
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: false
  };
}

function makeStepperConfig(): StepperConfiguration {
  return {
    showEditorSource: true,
    showEditorTarget: true,
    allowTestSending: true,
    allowTestTransformation: true
  };
}

/** Minimal formly-style model that isSubstitutionValid reads */
function makeSubstitutionModel(
  sourceValid: boolean,
  targetValid: boolean,
  pathSource = '$.temp',
  pathTarget = '$.value'
) {
  return {
    pathSource,
    pathTarget,
    sourceExpression: { valid: sourceValid },
    targetExpression: { valid: targetValid }
  };
}

// ---------------------------------------------------------------------------
// SubstitutionManagementService
// ---------------------------------------------------------------------------

describe('SubstitutionManagementService', () => {
  let service: SubstitutionManagementService;
  let mockModalService: jasmine.SpyObj<BsModalService>;

  beforeEach(() => {
    mockModalService = jasmine.createSpyObj<BsModalService>('BsModalService', ['show']);

    TestBed.configureTestingModule({
      providers: [
        SubstitutionManagementService,
        { provide: BsModalService, useValue: mockModalService }
      ]
    });

    service = TestBed.inject(SubstitutionManagementService);
  });

  // -------------------------------------------------------------------------
  // isSubstitutionValid
  // -------------------------------------------------------------------------

  describe('isSubstitutionValid', () => {
    it('should return true when both expressions are valid and paths are non-empty', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(true, true))).toBe(true);
    });

    it('should return false when source expression is invalid', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(false, true))).toBe(false);
    });

    it('should return false when target expression is invalid', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(true, false))).toBe(false);
    });

    it('should return false when pathSource is empty', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(true, true, '', '$.value'))).toBe(false);
    });

    it('should return false when pathTarget is empty', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(true, true, '$.temp', ''))).toBe(false);
    });

    it('should return false when both paths are empty', () => {
      expect(service.isSubstitutionValid(makeSubstitutionModel(true, true, '', ''))).toBe(false);
    });
  });

  // -------------------------------------------------------------------------
  // addSubstitution — non-expert mode, no duplicate
  // -------------------------------------------------------------------------

  describe('addSubstitution (non-expert mode, no duplicate)', () => {
    it('should push substitution to mapping and call onSuccess', () => {
      const mapping = makeMapping();
      const model = makeSubstitutionModel(true, true, '$.temp', '$.c8y_Temp.T.value');
      const onSuccess = jasmine.createSpy('onSuccess');

      service.addSubstitution(model, mapping, makeStepperConfig(), false, onSuccess);

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.temp');
      expect(mapping.substitutions[0].pathTarget).toBe('$.c8y_Temp.T.value');
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(mockModalService.show).not.toHaveBeenCalled();
    });

    it('should not open a modal when expertMode is false and no duplicate exists', () => {
      const mapping = makeMapping();
      service.addSubstitution(
        makeSubstitutionModel(true, true),
        mapping,
        makeStepperConfig(),
        false,
        () => {}
      );
      expect(mockModalService.show).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // addSubstitution — non-expert mode, duplicate target path
  // -------------------------------------------------------------------------

  describe('addSubstitution (non-expert mode, duplicate target path)', () => {
    it('should open the edit-substitution modal when a duplicate target path exists', () => {
      const existing = makeSubstitution('$.old', '$.value');
      const mapping = makeMapping([existing]);
      const model = makeSubstitutionModel(true, true, '$.new', '$.value'); // same target path

      // Modal needs a content with closeSubject; provide a minimal stub
      const mockModalContent = { closeSubject: { pipe: () => ({ subscribe: () => {} }) } };
      mockModalService.show.and.returnValue({ content: mockModalContent } as any);

      service.addSubstitution(model, mapping, makeStepperConfig(), false, () => {});

      expect(mockModalService.show).toHaveBeenCalledTimes(1);
      const [, options] = mockModalService.show.calls.mostRecent().args as any[];
      expect(options.initialState.isDuplicate).toBe(true);
      expect(options.initialState.duplicateSubstitutionIndex).toBe(0);
    });
  });

  // -------------------------------------------------------------------------
  // addSubstitution — expert mode, no duplicate
  // -------------------------------------------------------------------------

  describe('addSubstitution (expert mode, no duplicate)', () => {
    it('should open the modal even when there is no duplicate', () => {
      const mapping = makeMapping();
      const model = makeSubstitutionModel(true, true);

      const mockModalContent = { closeSubject: { pipe: () => ({ subscribe: () => {} }) } };
      mockModalService.show.and.returnValue({ content: mockModalContent } as any);

      service.addSubstitution(model, mapping, makeStepperConfig(), true, () => {});

      expect(mockModalService.show).toHaveBeenCalledTimes(1);
      const [, options] = mockModalService.show.calls.mostRecent().args as any[];
      expect(options.initialState.isDuplicate).toBe(false);
    });
  });

  // -------------------------------------------------------------------------
  // updateSubstitution — guard: does nothing when index is -1
  // -------------------------------------------------------------------------

  describe('updateSubstitution', () => {
    it('should do nothing when selectedSubstitution is -1', () => {
      const mapping = makeMapping([makeSubstitution()]);
      const model = makeSubstitutionModel(true, true, '$.new', '$.other');

      service.updateSubstitution(-1, model, mapping, makeStepperConfig(), () => {});

      expect(mockModalService.show).not.toHaveBeenCalled();
    });

    it('should open the modal for a valid index', () => {
      const mapping = makeMapping([makeSubstitution('$.src', '$.tgt')]);
      const model = makeSubstitutionModel(true, true, '$.src2', '$.tgt2');

      const mockModalContent = { closeSubject: { pipe: () => ({ subscribe: () => {} }) } };
      mockModalService.show.and.returnValue({ content: mockModalContent } as any);

      service.updateSubstitution(0, model, mapping, makeStepperConfig(), () => {});

      expect(mockModalService.show).toHaveBeenCalledTimes(1);
      const [, options] = mockModalService.show.calls.mostRecent().args as any[];
      expect(options.initialState.isUpdate).toBe(true);
    });
  });
});
