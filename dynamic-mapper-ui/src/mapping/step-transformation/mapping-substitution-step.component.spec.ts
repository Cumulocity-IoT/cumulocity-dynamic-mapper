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

import '../../shared';
import { MappingSubstitutionStepComponent } from './mapping-substitution-step.component';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { RepairStrategy, Substitution } from '../../shared';
import { interpolateMessage } from '../../shared/testing/translate-stub';

describe('MappingSubstitutionStepComponent showExpandArrayHint', () => {
  // The getter only reads substitutionModel, so no TestBed (and no CoreModule DI graph) is needed.
  const hintFor = (substitutionModel: any): boolean => {
    const component = Object.create(MappingSubstitutionStepComponent.prototype);
    component.substitutionModel = substitutionModel;
    return component.showExpandArrayHint;
  };

  it('is shown when the source expression yields an array and expand-as-array is off', () => {
    expect(hintFor({ sourceExpression: { resultType: 'Array' }, expandArray: false })).toBeTrue();
    expect(hintFor({ sourceExpression: { resultType: 'Array' } })).toBeTrue();
  });

  it('is hidden while the expression is invalid, as the field error takes its place', () => {
    expect(hintFor({ sourceExpression: { resultType: 'Array', valid: false } })).toBeFalse();
    expect(hintFor({ sourceExpression: { resultType: 'Array', valid: true } })).toBeTrue();
  });

  it('is hidden once expand-as-array is on', () => {
    expect(hintFor({ sourceExpression: { resultType: 'Array' }, expandArray: true })).toBeFalse();
  });

  it('is hidden for non-array results and before anything was evaluated', () => {
    expect(hintFor({ sourceExpression: { resultType: 'String' } })).toBeFalse();
    expect(hintFor({})).toBeFalse();
    expect(hintFor(undefined)).toBeFalse();
  });
});

describe('MappingSubstitutionStepComponent substitution changes', () => {
  const sub = (pathSource: string, pathTarget: string): Substitution => ({
    pathSource, pathTarget, repairStrategy: RepairStrategy.DEFAULT, expandArray: false
  });

  let step: any;
  let emitted: Substitution[][];
  let alertService: jasmine.SpyObj<any>;
  let refresh: jasmine.Spy;
  let mapping: any;

  beforeEach(() => {
    // The step is exercised without a TestBed (no CoreModule DI graph): only what the methods read is set.
    step = Object.create(MappingSubstitutionStepComponent.prototype);
    mapping = { substitutions: [sub('$.a', '$.tgtA'), sub('$.b', '$.tgtB')] };
    emitted = [];
    alertService = jasmine.createSpyObj('AlertService', ['info', 'warning']);
    refresh = jasmine.createSpy('refreshSubstitutionValidity');
    step.mapping = mapping;
    step.substitutionService = new SubstitutionManagementService();
    step.alertService = alertService;
    step.translateService = { instant: interpolateMessage };
    step.refreshSubstitutionValidity = refresh;
    step.substitutionsChange = { emit: (s: Substitution[]) => emitted.push(s) };
    step.selectedSubstitution = -1;
  });

  it('delete announces the remaining list without touching the mapping, then revalidates', () => {
    step.selectedSubstitution = 1;

    step.onDeleteSubstitution(0);

    expect(emitted.length).toBe(1);
    expect(emitted[0].map(s => s.pathSource)).toEqual(['$.b']);
    expect(mapping.substitutions.length).toBe(2); // the host applies the change
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(step.selectedSubstitution).toBe(0); // a row above the selected one was removed
  });

  it('delete of an index outside the list announces nothing', () => {
    step.onDeleteSubstitution(7);

    expect(emitted).toEqual([]);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('an in-grid edit is announced as a new list and leaves the old substitution untouched', () => {
    const original = mapping.substitutions[1];

    step.onGridSubstitutionPatch({ index: 1, patch: { expandArray: true } });

    expect(emitted[0][1].expandArray).toBeTrue();
    expect(original.expandArray).toBeFalse();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('a replaced duplicate target is reported to the user', () => {
    step.substitutionModel = { pathSource: '$.new', pathTarget: '$.tgtA', sourceExpression: { valid: true }, targetExpression: { valid: true } };

    step.onAddSubstitution();

    expect(emitted[0].map(s => s.pathSource)).toEqual(['$.new', '$.b']);
    expect(alertService.info).toHaveBeenCalledWith(jasmine.stringContaining('Replaced substitution # 1'));
    expect(step.selectedSubstitution).toBe(-1);
  });

  it('adding an invalid substitution warns and announces nothing', () => {
    step.substitutionModel = { pathSource: '', pathTarget: '', sourceExpression: { valid: true }, targetExpression: { valid: true } };

    step.onAddSubstitution();

    expect(alertService.warning).toHaveBeenCalled();
    expect(emitted).toEqual([]);
  });

  it('update announces the list, then leaves edit mode', () => {
    step.selectedSubstitution = 0;
    step.substitutionModel = { pathSource: '$.a2', pathTarget: '$.tgtA2', sourceExpression: { valid: true }, targetExpression: { valid: true } };
    step.cancelEditSubstitution = jasmine.createSpy('cancelEditSubstitution');

    step.onUpdateSubstitution();

    expect(emitted[0][0]).toEqual(jasmine.objectContaining({ pathSource: '$.a2', pathTarget: '$.tgtA2' }));
    expect(step.cancelEditSubstitution).toHaveBeenCalled();
  });

  it('update with no selected row does nothing', () => {
    step.cancelEditSubstitution = jasmine.createSpy('cancelEditSubstitution');
    step.substitutionModel = { pathSource: 'x', pathTarget: 'y', sourceExpression: { valid: true }, targetExpression: { valid: true } };

    step.onUpdateSubstitution();

    expect(emitted).toEqual([]);
    expect(step.cancelEditSubstitution).not.toHaveBeenCalled();
  });
});
