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

import { Direction } from '../../shared';
import {
  CodeTemplate,
  CodeTemplateMap,
  TemplateType,
  UNDECODABLE_TEMPLATE_CODE,
  decodeCodeTemplate,
  decodeCodeTemplates
} from './configuration.model';

/**
 * The Code Templates screen PUTs the decoded object straight back, so anything the decoder drops
 * is silently erased on the next save. Two hand-rolled copies of this used to rebuild the object
 * field-by-field: both omitted `direction` and hard-coded `defaultTemplate: false`, so saving a
 * default template cleared its own default flag — which the backend consults when deciding
 * whether a newly shipped template of that type still needs installing.
 */
describe('decodeCodeTemplate', () => {
  const decode = (code: string) => atob(code);

  function template(overrides: Partial<CodeTemplate> = {}): CodeTemplate {
    return {
      id: 'stored-id',
      name: 'My template',
      description: 'A description',
      templateType: TemplateType.INBOUND_SMART_FUNCTION,
      direction: Direction.INBOUND,
      code: btoa('function onMessage() {}'),
      internal: true,
      readonly: true,
      defaultTemplate: true,
      ...overrides
    };
  }

  it('decodes the body and keys the result by the map key', () => {
    const result = decodeCodeTemplate('map-key', template(), decode);

    expect(result.code).toBe('function onMessage() {}');
    expect(result.id).toBe('map-key');
  });

  it('preserves defaultTemplate instead of forcing it false', () => {
    expect(decodeCodeTemplate('k', template({ defaultTemplate: true }), decode).defaultTemplate).toBe(true);
    expect(decodeCodeTemplate('k', template({ defaultTemplate: false }), decode).defaultTemplate).toBe(false);
  });

  it('preserves direction, which was dropped entirely', () => {
    expect(decodeCodeTemplate('k', template({ direction: Direction.OUTBOUND }), decode).direction)
      .toBe(Direction.OUTBOUND);
  });

  it('preserves every other field verbatim', () => {
    const source = template();

    const result = decodeCodeTemplate('k', source, decode);

    expect(result.name).toBe(source.name);
    expect(result.description).toBe(source.description);
    expect(result.templateType).toBe(source.templateType);
    expect(result.internal).toBe(true);
    expect(result.readonly).toBe(true);
  });

  it('does not mutate the stored template', () => {
    const source = template();
    const encoded = source.code;

    decodeCodeTemplate('k', source, decode);

    expect(source.code).toBe(encoded);
  });

  it('falls back to a placeholder body but keeps the metadata when the code will not decode', () => {
    const result = decodeCodeTemplate('k', template({ defaultTemplate: true }), () => {
      throw new Error('not base64');
    });

    expect(result.code).toBe(UNDECODABLE_TEMPLATE_CODE);
    // Losing the flags here would corrupt the template on the next save just the same.
    expect(result.defaultTemplate).toBe(true);
    expect(result.readonly).toBe(true);
  });
});

describe('decodeCodeTemplates', () => {
  const decode = (code: string) => atob(code);

  const map: CodeTemplateMap = {
    a: {
      id: 'a', name: 'A', templateType: TemplateType.SHARED, code: btoa('shared'),
      internal: false, readonly: false, defaultTemplate: true
    },
    b: {
      id: 'b', name: 'B', templateType: TemplateType.SYSTEM, code: btoa('system'),
      internal: true, readonly: true, defaultTemplate: true
    }
  };

  it('decodes every entry, keyed by its map key', () => {
    const result = decodeCodeTemplates(map, decode);

    expect(result.size).toBe(2);
    expect(result.get('a')!.code).toBe('shared');
    expect(result.get('b')!.code).toBe('system');
  });

  it('returns a fresh Map so a template deleted since the last refresh cannot linger', () => {
    const first = decodeCodeTemplates(map, decode);
    const second = decodeCodeTemplates({ a: map['a'] }, decode);

    expect(first.size).toBe(2);
    expect(second.size).toBe(1);
    expect(second.has('b')).toBe(false);
  });

  it('tolerates a missing map', () => {
    expect(decodeCodeTemplates(undefined as any, decode).size).toBe(0);
  });
});
