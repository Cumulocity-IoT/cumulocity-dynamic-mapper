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
import { AlertService } from '@c8y/ngx-components';
import { SubstitutionManagementService } from './substitution-management.service';
import {
  Direction,
  Mapping,
  MappingType,
  RepairStrategy,
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
  let mockAlertService: jasmine.SpyObj<AlertService>;

  beforeEach(() => {
    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', ['info']);

    TestBed.configureTestingModule({
      providers: [
        SubstitutionManagementService,
        { provide: AlertService, useValue: mockAlertService }
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
  // addSubstitution — no duplicate
  // -------------------------------------------------------------------------

  describe('addSubstitution (no duplicate)', () => {
    it('should push substitution to mapping and call onSuccess', () => {
      const mapping = makeMapping();
      const model = makeSubstitutionModel(true, true, '$.temp', '$.c8y_Temp.T.value');
      const onSuccess = jasmine.createSpy('onSuccess');

      service.addSubstitution(model, mapping, onSuccess);

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.temp');
      expect(mapping.substitutions[0].pathTarget).toBe('$.c8y_Temp.T.value');
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(mockAlertService.info).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // addSubstitution — duplicate target path
  // -------------------------------------------------------------------------

  describe('addSubstitution (duplicate target path)', () => {
    // The confirmation modal this used to open is gone: expandArray/repairStrategy are edited
    // inline in the grid, and the replaced row is visible there, so the overwrite is applied
    // directly and only reported via an alert.
    it('should replace the colliding substitution in place and report it', () => {
      const existing = makeSubstitution('$.old', '$.value');
      const mapping = makeMapping([existing]);
      const model = makeSubstitutionModel(true, true, '$.new', '$.value'); // same target path
      const onSuccess = jasmine.createSpy('onSuccess');

      service.addSubstitution(model, mapping, onSuccess);

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.new');
      expect(mapping.substitutions[0].pathTarget).toBe('$.value');
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(mockAlertService.info).toHaveBeenCalledTimes(1);
    });

    it('should replace at the colliding index, leaving other substitutions in place', () => {
      const mapping = makeMapping([
        makeSubstitution('$.a', '$.tgtA'),
        makeSubstitution('$.b', '$.tgtB')
      ]);

      service.addSubstitution(makeSubstitutionModel(true, true, '$.b2', '$.tgtB'), mapping, () => {});

      expect(mapping.substitutions.length).toBe(2);
      expect(mapping.substitutions[0].pathSource).toBe('$.a');
      expect(mapping.substitutions[1].pathSource).toBe('$.b2');
    });
  });

  // -------------------------------------------------------------------------
  // updateSubstitution — guard: does nothing when index is -1
  // -------------------------------------------------------------------------

  describe('updateSubstitution', () => {
    it('should do nothing when selectedSubstitution is -1', () => {
      const mapping = makeMapping([makeSubstitution()]);
      const model = makeSubstitutionModel(true, true, '$.new', '$.other');
      const onSuccess = jasmine.createSpy('onSuccess');

      service.updateSubstitution(-1, model, mapping, onSuccess);

      expect(onSuccess).not.toHaveBeenCalled();
      expect(mapping.substitutions[0].pathSource).toBe('$.temp');
    });

    it('should apply the update directly when there is no duplicate target path', () => {
      const mapping = makeMapping([makeSubstitution('$.src', '$.tgt')]);
      const model = makeSubstitutionModel(true, true, '$.src2', '$.tgt2');
      const onSuccess = jasmine.createSpy('onSuccess');

      service.updateSubstitution(0, model, mapping, onSuccess);

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.src2');
      expect(mapping.substitutions[0].pathTarget).toBe('$.tgt2');
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(mockAlertService.info).not.toHaveBeenCalled();
    });

    it('should preserve the existing repairStrategy/expandArray (now edited inline in the grid) when applied directly', () => {
      const existing = { ...makeSubstitution('$.src', '$.tgt'), repairStrategy: RepairStrategy.IGNORE, expandArray: true };
      const mapping = makeMapping([existing]);
      const model = makeSubstitutionModel(true, true, '$.src2', '$.tgt2');

      service.updateSubstitution(0, model, mapping, () => {});

      expect(mapping.substitutions[0].repairStrategy).toBe(RepairStrategy.IGNORE);
      expect(mapping.substitutions[0].expandArray).toBe(true);
    });

    it('should collapse the edited row into the colliding slot and report it', () => {
      const other = makeSubstitution('$.other', '$.tgt2');
      const editing = makeSubstitution('$.src', '$.tgt');
      const mapping = makeMapping([editing, other]);
      const model = makeSubstitutionModel(true, true, '$.src2', '$.tgt2'); // collides with `other`
      const onSuccess = jasmine.createSpy('onSuccess');

      service.updateSubstitution(0, model, mapping, onSuccess);

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.src2');
      expect(mapping.substitutions[0].pathTarget).toBe('$.tgt2');
      expect(onSuccess).toHaveBeenCalledTimes(1);
      expect(mockAlertService.info).toHaveBeenCalledTimes(1);
    });

    it('should collapse correctly when the colliding entry precedes the edited one', () => {
      const other = makeSubstitution('$.other', '$.tgt2');
      const editing = makeSubstitution('$.src', '$.tgt');
      const mapping = makeMapping([other, editing]);
      const model = makeSubstitutionModel(true, true, '$.src2', '$.tgt2');

      service.updateSubstitution(1, model, mapping, () => {});

      expect(mapping.substitutions.length).toBe(1);
      expect(mapping.substitutions[0].pathSource).toBe('$.src2');
    });
  });
});
