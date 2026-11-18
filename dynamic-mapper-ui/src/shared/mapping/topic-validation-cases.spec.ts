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
 */

import { FormControl, FormGroup } from '@angular/forms';
// Import the barrel first: mapping.model and processor.model import each other's values.
import { checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from './util';
import { ValidationFormlyError } from './validation-errors';
import topicCases from '../../../../resources/validation/topic-cases.json';
import errorCodes from '../../../../resources/validation/error-codes.json';

/**
 * Runs the validation cases that the backend runs too (resources/validation, repository root). The
 * backend counterpart is MappingValidatorSharedCasesTest; a case added to the file must pass in both.
 */
interface TopicCase {
  topic: string;
  sample: string;
  valid: boolean;
  error?: string;
}

describe('shared topic validation cases', () => {
  const inbound = (c: TopicCase) =>
    checkTopicsInboundAreValid(
      new FormGroup({ mappingTopic: new FormControl(c.topic), mappingTopicSample: new FormControl(c.sample) })
    );
  const outbound = (c: TopicCase) =>
    checkTopicsOutboundAreValid(
      new FormGroup({ publishTopic: new FormControl(c.topic), publishTopicSample: new FormControl(c.sample) })
    );

  for (const [direction, validate, cases] of [
    ['inbound', inbound, topicCases.inbound as TopicCase[]],
    ['outbound', outbound, topicCases.outbound as TopicCase[]]
  ] as const) {
    for (const c of cases) {
      it(`${direction}: ${c.topic} | ${c.sample} -> ${c.valid ? 'valid' : c.error}`, () => {
        const errors = validate(c) as Record<string, unknown> | null;
        if (c.valid) {
          expect(errors).toBeNull();
        } else {
          expect(errors).not.toBeNull();
          expect(Object.keys(errors ?? {})).toContain(c.error);
        }
      });
    }
  }

  it('the error catalog matches the shared list of error codes', () => {
    expect(Object.keys(ValidationFormlyError).sort()).toEqual([...errorCodes.codes].sort());
  });
});
