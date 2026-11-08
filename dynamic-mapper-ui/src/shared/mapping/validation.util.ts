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
import { AbstractControl } from '@angular/forms';
import * as _ from 'lodash';
import { Mapping } from './mapping.model';
import { ValidationFormlyError } from './validation-errors';
import { PROTECTED_TOKENS } from './processor/processor.constants';
import { splitTopicExcludingSeparator } from './topic.util';
import { TOPIC_WILDCARD_MULTI, TOPIC_WILDCARD_SINGLE } from './util-constants';

// ─── Mapping validators ───────────────────────────────────────────────────────

export function isMappingTopicUnique(
  mapping: Mapping,
  mappings: Mapping[]
): boolean {
  const result = mappings.every((m) => {
    return (
      (!mapping.mappingTopic.startsWith(m.mappingTopic) &&
        !m.mappingTopic.startsWith(mapping.mappingTopic)) ||
      mapping.id == m.id
    );
  });
  return result;
}

export function isFilterOutboundUnique(
  mapping: Mapping,
  mappings: Mapping[]
): boolean {
  let result = true;
  result = mappings.every((m) => {
    return mapping.filterMapping != m.filterMapping || mapping.id == m.id;
  });
  return result;
}

// ─── Backend error-code translation ────────────────────────────────────────────

/**
 * Translates a single backend `ValidationError` code (e.g. `"MappingTopic_And_..."`)
 * into the human-readable message from `ValidationFormlyError`. Falls back to a
 * de-slugified version of the code itself (underscores → spaces) for any code the
 * catalogue doesn't (yet) know about, rather than showing the raw enum token verbatim.
 */
export function translateValidationErrorCode(code: string): string {
  return ValidationFormlyError[code]?.message ?? code.replace(/_/g, ' ');
}

/**
 * Builds a single human-readable message from a backend error response body.
 *
 * The mapping create/update/publish endpoints return a structured 422 body
 * (`{ message, errors: string[] }`, see `ValidationErrorResponse` on the backend) on a
 * validation failure — `errors` are translated individually and joined. Any other
 * error shape (e.g. a plain `{ message }` from a different failure) falls back to that
 * message, or to `fallback` if the body has neither.
 */
export function buildBackendErrorMessage(body: any, fallback: string): string {
  if (Array.isArray(body?.errors) && body.errors.length > 0) {
    return body.errors.map((code: string) => translateValidationErrorCode(code)).join('; ');
  }
  return body?.message ?? fallback;
}

// ─── Form validators ──────────────────────────────────────────────────────────

/**
 * Propagates each group-level validation error onto the specific sub-control named by
 * its `errorPath` (set alongside every entry added in `checkTopicsInboundAreValid` /
 * checkTopicsOutboundAreValid`), in addition to the group-level result those functions
 * return. Without this, only the group as a whole is marked invalid — Formly's
 * per-field `c8y-form-field` error rendering (which reads `control.errors`, not the
 * parent group's) never shows *why*, so the save button is disabled with no visible
 * reason on the wildcard/topic-sample-mismatch checks (previously only the two
 * `required` checks propagated to a control, via their own early-return `setErrors`
 * calls above).
 */
function propagateErrorsToControls(control: AbstractControl, errors: Record<string, { errorPath: string }>): void {
  const byControlPath: Record<string, Record<string, unknown>> = {};
  Object.entries(errors).forEach(([key, err]) => {
    byControlPath[err.errorPath] = { ...byControlPath[err.errorPath], [key]: err };
  });
  Object.entries(byControlPath).forEach(([path, controlErrors]) => {
    control['controls'][path]?.setErrors(controlErrors);
  });
}

export function checkTopicsInboundAreValid(control: AbstractControl) {
  let errors = {};
  let error: boolean = false;

  // console.log('Validation options:', options);

  const { mappingTopic, mappingTopicSample } =
    control['controls'];
  mappingTopic.setErrors(null);
  mappingTopicSample.setErrors(null);

  // Propagate required errors onto individual controls so Formly's c8y-form-field
  // shows the error and propertyFormly.invalid is true. Returning { required: false }
  // from the group validator is ineffective — Formly strips falsy-value keys.
  if (!mappingTopic.value) {
    mappingTopic.setErrors({ required: true });
    return null;
  }
  if (!mappingTopicSample.value) {
    mappingTopicSample.setErrors({ required: true });
    return null;
  }

  // count number of "#" in mappingTopic
  let count_multi = (mappingTopic.value.match(/#/g) || []).length;
  if (count_multi > 1) {
    errors = {
      ...errors,
      Only_One_Multi_Level_Wildcard: {
        ...ValidationFormlyError['Only_One_Multi_Level_Wildcard'],
        errorPath: 'mappingTopic'
      }
    };
  }

  // wildcard "#" can only appear at the end in mappingTopic
  if (
    count_multi >= 1 &&
    mappingTopic.value.indexOf(TOPIC_WILDCARD_MULTI) + 1 !=
    mappingTopic.value.length
  ) {
    errors = {
      ...errors,
      Multi_Level_Wildcard_Only_At_End: {
        ...ValidationFormlyError['Multi_Level_Wildcard_Only_At_End'],
        errorPath: 'mappingTopic'
      }
    };
  }

  const splitTT: string[] = splitTopicExcludingSeparator(mappingTopic.value, false);
  const splitTTS: string[] = splitTopicExcludingSeparator(
    mappingTopicSample.value, false
  );

  // MQTT semantics: a trailing "#" matches any number (incl. zero) of remaining
  // levels, so the sample only needs to have AT LEAST as many levels as the
  // fixed (non-"#") part of the topic. Without a "#" the level count must match
  // exactly. "+" matches exactly one level regardless of its value.
  const hasMultiWildcard = splitTT[splitTT.length - 1] == TOPIC_WILDCARD_MULTI;
  const fixedTT = hasMultiWildcard ? splitTT.slice(0, -1) : splitTT;

  if (
    hasMultiWildcard
      ? splitTTS.length < fixedTT.length
      : splitTTS.length != fixedTT.length
  ) {
    errors = {
      ...errors,
      MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
      {
        ...ValidationFormlyError[
        'MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name'
        ],
        errorPath: 'mappingTopic'
      }
    };
  } else {
    for (let i = 0; i < fixedTT.length; i++) {
      if ('/' == fixedTT[i] && !('/' == splitTTS[i])) {
        errors = {
          ...errors,
          MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
          {
            ...ValidationFormlyError[
            'MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
            ],
            errorPath: 'mappingTopic'
          }
        };
        break;
      }
      if ('/' == splitTTS[i] && !('/' == fixedTT[i])) {
        errors = {
          ...errors,
          MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
          {
            ...ValidationFormlyError[
            'MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
            ],
            errorPath: 'mappingTopic'
          }
        };
        break;
      }
      if (
        !('/' == fixedTT[i]) &&
        !(TOPIC_WILDCARD_SINGLE == fixedTT[i]) &&
        !(TOPIC_WILDCARD_MULTI == fixedTT[i])
      ) {
        if (fixedTT[i] != splitTTS[i]) {
          errors = {
            ...errors,
            MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
              'MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
              ],
              errorPath: 'mappingTopic'
            }
          };
          break;
        }
      }
    }
  }
  propagateErrorsToControls(control, errors);
  return Object.keys(errors).length > 0 ? errors : null;
}

export function checkTopicsOutboundAreValid(control: AbstractControl) {
  let errors = {};
  const { publishTopic, publishTopicSample } = control['controls'];
  if (publishTopic.valid && publishTopicSample.value) {
    publishTopic.setErrors(null);
    publishTopicSample.setErrors(null);

    // avoid displaying the message error when values are empty
    if (publishTopic.value == '' || publishTopicSample.value == '') {
      return null;
    }

    // count number of "#" in publishTopic
    const count_multi = (publishTopic.value?.match(/#/g) || []).length;
    if (count_multi > 1) {
      errors = {
        ...errors,
        Only_One_Multi_Level_Wildcard: {
          ...ValidationFormlyError['Only_One_Multi_Level_Wildcard'],
          errorPath: 'publishTopic'
        }
      };
    }

    // wildcard "#" can only appear at the end in mappingTopic
    if (
      count_multi >= 1 &&
      publishTopic.value.indexOf(TOPIC_WILDCARD_MULTI) + 1 !=
      publishTopic.value.length
    ) {
      errors = {
        ...errors,
        Multi_Level_Wildcard_Only_At_End: {
          ...ValidationFormlyError['Multi_Level_Wildcard_Only_At_End'],
          errorPath: 'publishTopic'
        }
      };
    }

    const splitPT: string[] = splitTopicExcludingSeparator(publishTopic.value, false);
    const splitTTS: string[] = splitTopicExcludingSeparator(
      publishTopicSample.value, false
    );
    if (splitPT.length != splitTTS.length) {
      errors = {
        ...errors,
        PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
        {
          ...ValidationFormlyError[
          'PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name'
          ],
          errorPath: 'publishTopicSample'
        }
      };
    } else {
      for (let i = 0; i < splitPT.length; i++) {
        if ('/' == splitPT[i] && !('/' == splitTTS[i])) {
          errors = {
            ...errors,
            PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
              'PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
              ],
              errorPath: 'publishTopicSample'
            }
          };
          break;
        }
        if ('/' == splitTTS[i] && !('/' == splitPT[i])) {
          errors = {
            ...errors,
            PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
              'PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
              ],
              errorPath: 'publishTopicSample'
            }
          };
          break;
        }
        if (
          !('/' == splitPT[i]) &&
          !('+' == splitPT[i]) &&
          !('#' == splitPT[i])
        ) {
          if (splitPT[i] != splitTTS[i]) {
            errors = {
              ...errors,
              PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
              {
                ...ValidationFormlyError[
                'PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name'
                ],
                errorPath: 'publishTopicSample'
              }
            };
            break;
          }
        }
      }
    }
  }

  propagateErrorsToControls(control, errors);
  return Object.keys(errors).length > 0 ? errors : null;
}

export function validateProtectedFields(original: any, updated: any): boolean {
  for (const field of PROTECTED_TOKENS) {
    const originalValue = findFieldInObject(original, field);
    const updatedValue = findFieldInObject(updated, field);

    if (originalValue !== undefined && !_.isEqual(originalValue, updatedValue)) {
      return false;
    }
  }

  return true;
}

function findFieldInObject(obj: any, fieldName: string): any {
  if (!obj || typeof obj !== 'object') {
    return undefined;
  }

  if (obj.hasOwnProperty(fieldName)) {
    return obj[fieldName];
  }

  for (const key in obj) {
    if (obj.hasOwnProperty(key) && typeof obj[key] === 'object') {
      const result = findFieldInObject(obj[key], fieldName);
      if (result !== undefined) {
        return result;
      }
    }
  }

  return undefined;
}
