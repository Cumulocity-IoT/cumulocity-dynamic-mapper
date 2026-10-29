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
import { ActivatedRouteSnapshot, convertToParamMap, RouterStateSnapshot } from '@angular/router';
import { Direction, MappingType, SharedService, TransformationType } from '../../shared';
import { MappingService } from './mapping.service';
import { MappingEditData, mappingEditResolver } from './mapping-edit.resolver';

/**
 * The resolver decides how the unified editor persists and whether it is editable. Every edit of
 * an existing mapping goes to its draft; read-only is the only thing that varies.
 */
describe('mappingEditResolver', () => {
  const buildMapping = (overrides: any = {}) => ({
    id: 'm1',
    identifier: 'id-1',
    name: 'Mapping',
    direction: Direction.INBOUND,
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    active: true,
    ...overrides
  });

  const resolve = async (
    mapping: any,
    features: { userHasMappingAdminRole?: boolean; userHasMappingCreateRole?: boolean }
  ): Promise<MappingEditData> => {
    const mappingService = jasmine.createSpyObj('MappingService', ['getMappings', 'getDefinedDeploymentMapEntry', 'getDraft']);
    mappingService.getMappings.and.resolveTo([mapping]);
    mappingService.getDefinedDeploymentMapEntry.and.resolveTo({ identifier: mapping.identifier, connectors: ['c1'] });
    mappingService.getDraft.and.resolveTo(null);
    const sharedService = jasmine.createSpyObj('SharedService', ['getFeatures']);
    sharedService.getFeatures.and.resolveTo(features);
    TestBed.configureTestingModule({
      providers: [
        { provide: MappingService, useValue: mappingService },
        { provide: SharedService, useValue: sharedService }
      ]
    });
    const route = {
      paramMap: convertToParamMap({ identifier: mapping.identifier }),
      data: { direction: Direction.INBOUND }
    } as unknown as ActivatedRouteSnapshot;
    return TestBed.runInInjectionContext(
      () => mappingEditResolver(route, {} as RouterStateSnapshot)
    ) as Promise<MappingEditData>;
  };

  it('saves to the draft and stays editable for an active mapping when the user can manage', async () => {
    const data = await resolve(buildMapping({ active: true }), { userHasMappingCreateRole: true });
    expect(data.stepperConfiguration.persistence).toBe('draft');
    expect(data.stepperConfiguration.readOnly).toBe(false);
  });

  it('saves to the draft for an inactive mapping too', async () => {
    const data = await resolve(buildMapping({ active: false }), { userHasMappingAdminRole: true });
    expect(data.stepperConfiguration.persistence).toBe('draft');
    expect(data.stepperConfiguration.readOnly).toBe(false);
  });

  it('is read-only for a user without a manage role', async () => {
    const data = await resolve(buildMapping(), {});
    expect(data.stepperConfiguration.readOnly).toBe(true);
  });

  it('is read-only for a deprecated SUBSTITUTION_AS_CODE mapping, whatever the role', async () => {
    const data = await resolve(buildMapping({ transformationType: TransformationType.SUBSTITUTION_AS_CODE }), { userHasMappingAdminRole: true });
    expect(data.stepperConfiguration.readOnly).toBe(true);
  });
});
