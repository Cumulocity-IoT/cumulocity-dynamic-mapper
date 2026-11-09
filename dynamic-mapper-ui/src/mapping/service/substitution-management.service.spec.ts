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

import { SubstitutionManagementService } from './substitution-management.service';
import { RepairStrategy, Substitution } from '../../shared';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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

  beforeEach(() => {
    service = new SubstitutionManagementService();
  });

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

  describe('purity', () => {
    it('never changes the list it is given', () => {
      const current = [makeSubstitution('$.a', '$.tgtA'), makeSubstitution('$.b', '$.tgtB')];
      const snapshot = JSON.stringify(current);

      service.addSubstitution(makeSubstitutionModel(true, true, '$.c', '$.tgtC'), current);
      service.addSubstitution(makeSubstitutionModel(true, true, '$.b2', '$.tgtB'), current);
      service.updateSubstitution(0, makeSubstitutionModel(true, true, '$.x', '$.tgtB'), current);
      service.deleteSubstitution(0, current);
      service.patchSubstitution(current, 1, { expandArray: true });

      expect(JSON.stringify(current)).toBe(snapshot);
    });
  });

  describe('addSubstitution', () => {
    it('appends a plain substitution without a notice', () => {
      const update = service.addSubstitution(makeSubstitutionModel(true, true, '$.temp', '$.c8y_Temp.T.value'), []);

      expect(update.substitutions).toEqual([
        jasmine.objectContaining({ pathSource: '$.temp', pathTarget: '$.c8y_Temp.T.value' })
      ]);
      expect(update.notice).toBeUndefined();
    });

    it('strips UI-only state from the model', () => {
      const model = { ...makeSubstitutionModel(true, true), stepperConfiguration: {}, pathSourceIsExpression: true };
      const [added] = service.addSubstitution(model as any, []).substitutions;
      expect(Object.keys(added).sort()).toEqual(['expandArray', 'pathSource', 'pathTarget', 'repairStrategy']);
    });

    it('replaces the colliding substitution in place and reports it', () => {
      const update = service.addSubstitution(
        makeSubstitutionModel(true, true, '$.new', '$.value'),
        [makeSubstitution('$.old', '$.value')]
      );

      expect(update.substitutions.length).toBe(1);
      expect(update.substitutions[0].pathSource).toBe('$.new');
      expect(update.notice).toContain('Replaced substitution # 1');
    });

    it('replaces at the colliding index, leaving other substitutions in place', () => {
      const update = service.addSubstitution(
        makeSubstitutionModel(true, true, '$.b2', '$.tgtB'),
        [makeSubstitution('$.a', '$.tgtA'), makeSubstitution('$.b', '$.tgtB')]
      );

      expect(update.substitutions.map(s => s.pathSource)).toEqual(['$.a', '$.b2']);
    });
  });

  describe('updateSubstitution', () => {
    it('returns undefined when nothing is selected', () => {
      const current = [makeSubstitution()];
      const model = makeSubstitutionModel(true, true, '$.new', '$.other');

      expect(service.updateSubstitution(-1, model, current)).toBeUndefined();
      expect(service.updateSubstitution(5, model, current)).toBeUndefined();
    });

    it('applies the update directly when there is no duplicate target path', () => {
      const update = service.updateSubstitution(
        0,
        makeSubstitutionModel(true, true, '$.src2', '$.tgt2'),
        [makeSubstitution('$.src', '$.tgt')]
      );

      expect(update.substitutions).toEqual([jasmine.objectContaining({ pathSource: '$.src2', pathTarget: '$.tgt2' })]);
      expect(update.notice).toBeUndefined();
    });

    it('preserves repairStrategy/expandArray, which are edited inline in the grid', () => {
      const existing = { ...makeSubstitution('$.src', '$.tgt'), repairStrategy: RepairStrategy.IGNORE, expandArray: true };

      const update = service.updateSubstitution(0, makeSubstitutionModel(true, true, '$.src2', '$.tgt2'), [existing]);

      expect(update.substitutions[0].repairStrategy).toBe(RepairStrategy.IGNORE);
      expect(update.substitutions[0].expandArray).toBe(true);
    });

    it('keeps the old paths when either expression is invalid', () => {
      const update = service.updateSubstitution(
        0,
        makeSubstitutionModel(false, true, '$.src2', '$.tgt2'),
        [makeSubstitution('$.src', '$.tgt')]
      );

      expect(update.substitutions[0]).toEqual(jasmine.objectContaining({ pathSource: '$.src', pathTarget: '$.tgt' }));
    });

    it('collapses the edited row into the colliding slot and reports it', () => {
      const update = service.updateSubstitution(
        0,
        makeSubstitutionModel(true, true, '$.src2', '$.tgt2'), // collides with the second entry
        [makeSubstitution('$.src', '$.tgt'), makeSubstitution('$.other', '$.tgt2')]
      );

      expect(update.substitutions.length).toBe(1);
      expect(update.substitutions[0]).toEqual(jasmine.objectContaining({ pathSource: '$.src2', pathTarget: '$.tgt2' }));
      expect(update.notice).toContain('Merged into substitution # 2');
    });

    it('collapses correctly when the colliding entry precedes the edited one', () => {
      const update = service.updateSubstitution(
        1,
        makeSubstitutionModel(true, true, '$.src2', '$.tgt2'),
        [makeSubstitution('$.other', '$.tgt2'), makeSubstitution('$.src', '$.tgt')]
      );

      expect(update.substitutions.length).toBe(1);
      expect(update.substitutions[0].pathSource).toBe('$.src2');
    });
  });

  describe('deleteSubstitution', () => {
    it('removes the substitution at the index', () => {
      const result = service.deleteSubstitution(
        1,
        [makeSubstitution('$.a', '$.tgtA'), makeSubstitution('$.b', '$.tgtB'), makeSubstitution('$.c', '$.tgtC')]
      );
      expect(result.map(s => s.pathSource)).toEqual(['$.a', '$.c']);
    });

    it('returns undefined for an index outside the list', () => {
      expect(service.deleteSubstitution(3, [makeSubstitution()])).toBeUndefined();
      expect(service.deleteSubstitution(-1, [makeSubstitution()])).toBeUndefined();
    });
  });

  describe('patchSubstitution', () => {
    it('changes only the addressed substitution', () => {
      const result = service.patchSubstitution(
        [makeSubstitution('$.a', '$.tgtA'), makeSubstitution('$.b', '$.tgtB')],
        1,
        { expandArray: true, repairStrategy: RepairStrategy.IGNORE }
      );

      expect(result[0].expandArray).toBe(false);
      expect(result[1]).toEqual(jasmine.objectContaining({ pathSource: '$.b', expandArray: true, repairStrategy: RepairStrategy.IGNORE }));
    });
  });

  describe('replaceAllSubstitutions', () => {
    it('maps every model to a plain substitution, without per-item duplicate handling', () => {
      const result = service.replaceAllSubstitutions([
        makeSubstitutionModel(true, true, '$.a', '$.same'),
        makeSubstitutionModel(true, true, '$.b', '$.same')
      ] as any);

      expect(result.map(s => s.pathSource)).toEqual(['$.a', '$.b']);
    });
  });
});
