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

import { FormControl, FormGroup } from '@angular/forms';
import { SnoopStatus } from '../../shared';
import {
  deriveSampleTopicFromTopic,
  isFilterOutboundUnique,
  isMappingTopicUnique,
  isWildcardTopic,
  normalizeTopic,
  splitTopicExcludingSeparator,
  splitTopicIncludingSeparator,
  checkNotSnooping,
  checkTopicsInboundAreValid
} from './util';
import { Direction, Mapping, MappingType, RepairStrategy, TransformationType } from '../../shared';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
    snoopedTemplates: [],
    snoopStatus: SnoopStatus.NONE,
    sourceTemplate: '{}',
    targetTemplate: '{}',
    mappingTopic: 'test/topic',
    mappingTopicSample: 'test/topic/sample',
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

// ---------------------------------------------------------------------------
// splitTopicExcludingSeparator
// ---------------------------------------------------------------------------

describe('splitTopicExcludingSeparator', () => {
  it('should return undefined for undefined input', () => {
    expect(splitTopicExcludingSeparator(undefined, false)).toBeUndefined();
  });

  it('should split a simple topic without leading slash', () => {
    expect(splitTopicExcludingSeparator('a/b/c', false)).toEqual(['a', 'b', 'c']);
  });

  it('should keep leading slash as first segment when cutOffLeadingSlash is false', () => {
    expect(splitTopicExcludingSeparator('/a/b', false)).toEqual(['/', 'a', 'b']);
  });

  it('should remove leading slash when cutOffLeadingSlash is true', () => {
    expect(splitTopicExcludingSeparator('/a/b', true)).toEqual(['a', 'b']);
  });

  it('should remove trailing slash', () => {
    expect(splitTopicExcludingSeparator('a/b/', false)).toEqual(['a', 'b']);
  });

  it('should handle single-segment topic', () => {
    expect(splitTopicExcludingSeparator('single', false)).toEqual(['single']);
  });

  it('should handle wildcards in topics', () => {
    expect(splitTopicExcludingSeparator('a/+/c', false)).toEqual(['a', '+', 'c']);
    expect(splitTopicExcludingSeparator('a/#', false)).toEqual(['a', '#']);
  });

  it('should trim leading/trailing whitespace', () => {
    expect(splitTopicExcludingSeparator('  a/b  ', false)).toEqual(['a', 'b']);
  });
});

// ---------------------------------------------------------------------------
// splitTopicIncludingSeparator
// ---------------------------------------------------------------------------

describe('splitTopicIncludingSeparator', () => {
  it('should split keeping separators as tokens', () => {
    const result = splitTopicIncludingSeparator('a/b/c');
    expect(result).toEqual(['a', '/', 'b', '/', 'c']);
  });

  it('should handle single segment', () => {
    const result = splitTopicIncludingSeparator('single');
    expect(result).toEqual(['single']);
  });
});

// ---------------------------------------------------------------------------
// normalizeTopic
// ---------------------------------------------------------------------------

describe('normalizeTopic', () => {
  it('should return empty string for undefined input', () => {
    expect(normalizeTopic(undefined)).toBe('');
  });

  it('should trim leading/trailing whitespace', () => {
    expect(normalizeTopic('  a/b  ')).toBe('a/b');
  });

  it('should reduce multiple leading slashes to one', () => {
    expect(normalizeTopic('//a/b')).toBe('/a/b');
  });

  it('should reduce multiple trailing slashes to one', () => {
    expect(normalizeTopic('a/b//')).toBe('a/b/');
  });

  it('should remove trailing slash after #', () => {
    expect(normalizeTopic('a/b/#/')).toBe('a/b/#');
  });

  it('should leave a normal topic unchanged', () => {
    expect(normalizeTopic('a/b/c')).toBe('a/b/c');
  });
});

// ---------------------------------------------------------------------------
// deriveSampleTopicFromTopic
// ---------------------------------------------------------------------------

describe('deriveSampleTopicFromTopic', () => {
  it('should return empty string for undefined input', () => {
    expect(deriveSampleTopicFromTopic(undefined)).toBe('');
  });

  it('should replace trailing # with +', () => {
    expect(deriveSampleTopicFromTopic('a/b/#')).toBe('a/b/+');
  });

  it('should leave topic without # unchanged', () => {
    expect(deriveSampleTopicFromTopic('a/b/c')).toBe('a/b/c');
  });

  it('should replace multiple trailing # signs with +', () => {
    expect(deriveSampleTopicFromTopic('a/b/###')).toBe('a/b/+');
  });
});

// ---------------------------------------------------------------------------
// isWildcardTopic
// ---------------------------------------------------------------------------

describe('isWildcardTopic', () => {
  it('should return true for topic with # wildcard', () => {
    expect(isWildcardTopic('a/b/#')).toBe(true);
  });

  it('should return true for topic with + wildcard', () => {
    expect(isWildcardTopic('a/+/c')).toBe(true);
  });

  it('should return false for topic without wildcards', () => {
    expect(isWildcardTopic('a/b/c')).toBe(false);
  });

  it('should return false for empty string', () => {
    expect(isWildcardTopic('')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// isMappingTopicUnique
// ---------------------------------------------------------------------------

describe('isMappingTopicUnique', () => {
  it('should return true when there are no other mappings', () => {
    const mapping = makeMapping({ mappingTopic: 'a/b/c' });
    expect(isMappingTopicUnique(mapping, [])).toBe(true);
  });

  it('should return true when topic does not overlap with others', () => {
    const mapping = makeMapping({ id: '1', mappingTopic: 'a/b/c' });
    const others = [makeMapping({ id: '2', mappingTopic: 'x/y/z' })];
    expect(isMappingTopicUnique(mapping, others)).toBe(true);
  });

  it('should return true when the only overlap is with itself', () => {
    const mapping = makeMapping({ id: '1', mappingTopic: 'a/b' });
    const others = [makeMapping({ id: '1', mappingTopic: 'a/b' })];
    expect(isMappingTopicUnique(mapping, others)).toBe(true);
  });

  it('should return false when another mapping has a prefix overlap', () => {
    const mapping = makeMapping({ id: '1', mappingTopic: 'a/b' });
    const others = [makeMapping({ id: '2', mappingTopic: 'a/b/c' })];
    expect(isMappingTopicUnique(mapping, others)).toBe(false);
  });

  it('should return false when this mapping is a prefix of another', () => {
    const mapping = makeMapping({ id: '1', mappingTopic: 'a/b/c' });
    const others = [makeMapping({ id: '2', mappingTopic: 'a/b' })];
    expect(isMappingTopicUnique(mapping, others)).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// isFilterOutboundUnique
// ---------------------------------------------------------------------------

describe('isFilterOutboundUnique', () => {
  it('should return true when there are no other mappings', () => {
    const mapping = makeMapping({ filterMapping: 'filter1' });
    expect(isFilterOutboundUnique(mapping, [])).toBe(true);
  });

  it('should return true when filter does not match any other mapping', () => {
    const mapping = makeMapping({ id: '1', filterMapping: 'filterA' });
    const others = [makeMapping({ id: '2', filterMapping: 'filterB' })];
    expect(isFilterOutboundUnique(mapping, others)).toBe(true);
  });

  it('should return true when matching mapping is itself', () => {
    const mapping = makeMapping({ id: '1', filterMapping: 'filterA' });
    const others = [makeMapping({ id: '1', filterMapping: 'filterA' })];
    expect(isFilterOutboundUnique(mapping, others)).toBe(true);
  });

  it('should return false when another mapping has the same filter', () => {
    const mapping = makeMapping({ id: '1', filterMapping: 'filterA' });
    const others = [makeMapping({ id: '2', filterMapping: 'filterA' })];
    expect(isFilterOutboundUnique(mapping, others)).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// checkNotSnooping (form validator)
// ---------------------------------------------------------------------------

describe('checkNotSnooping', () => {
  function makeControl(snoopStatusValue: SnoopStatus): FormGroup {
    return new FormGroup({
      snoopStatus: new FormControl(snoopStatusValue)
    });
  }

  it('should return null when snoopStatus is NONE', () => {
    expect(checkNotSnooping(makeControl(SnoopStatus.NONE))).toBeNull();
  });

  it('should return null when snoopStatus is STOPPED', () => {
    expect(checkNotSnooping(makeControl(SnoopStatus.STOPPED))).toBeNull();
  });

  it('should return error object when snoopStatus is ENABLED', () => {
    const result = checkNotSnooping(makeControl(SnoopStatus.ENABLED));
    expect(result).not.toBeNull();
    expect(result['Only_One_MuNot_Snooping']).toBeDefined();
  });

  it('should return error object when snoopStatus is STARTED', () => {
    const result = checkNotSnooping(makeControl(SnoopStatus.STARTED));
    expect(result).not.toBeNull();
    expect(result['Only_One_MuNot_Snooping']).toBeDefined();
  });
});

// ---------------------------------------------------------------------------
// checkTopicsInboundAreValid (form validator)
// ---------------------------------------------------------------------------

describe('checkTopicsInboundAreValid', () => {
  function makeControl(mappingTopic: string, mappingTopicSample: string): FormGroup {
    return new FormGroup({
      mappingTopic: new FormControl(mappingTopic),
      mappingTopicSample: new FormControl(mappingTopicSample)
    });
  }

  it('should return a falsy required error when either field is empty', () => {
    const result = checkTopicsInboundAreValid(makeControl('', 'a/b/c'));
    expect(result).toBeTruthy();
    expect(result['required']).toBe(false);
  });

  it('should return null for matching topic and sample', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/b/c', 'a/b/c'));
    expect(result).toBeNull();
  });

  it('should return null when mapping topic uses + wildcard matching sample segment', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/+/c', 'a/device1/c'));
    expect(result).toBeNull();
  });

  it('should return null when mapping topic uses # at end', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/b/#', 'a/b/anything'));
    expect(result).toBeNull();
  });

  it('should return error when mapping topic and sample have different number of levels', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/b', 'a/b/c'));
    expect(result).not.toBeNull();
    expect(
      result['MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name']
    ).toBeDefined();
  });

  it('should return error when # appears more than once in mapping topic', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/#/#', 'a/b/c'));
    expect(result).not.toBeNull();
  });

  it('should return error when mapping topic and sample have different static segments', () => {
    const result = checkTopicsInboundAreValid(makeControl('a/b/c', 'a/b/d'));
    expect(result).not.toBeNull();
    expect(
      result['MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name']
    ).toBeDefined();
  });
});
