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
import {
  API,
  Direction,
  Mapping,
  SnoopStatus
} from '../../shared';
import { IDENTITY } from '../../shared/mapping/mapping.model';
import { ValidationFormlyError } from './mapping.model';

export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const TIME = 'time';

export function splitTopicExcludingSeparator(topic: string, cutOffLeadingSlash: boolean): string[] {
  if (topic) {
    let topix = topic.trim();

    if (cutOffLeadingSlash) {
      // Original behavior: remove both leading and trailing slashes
      topix = topix.replace(/(\/{1,}$)|(^\/{1,})/g, '');
      return topix.split(/\//g);
    } else {
      // New behavior: keep leading slash, remove only trailing slashes
      topix = topix.replace(/\/{1,}$/g, '');
      if (topix.startsWith('//')) {
        topix = '/' + topix.replace(/^\/+/, '');
      }

      if (topix.startsWith('/')) {
        const parts = topix.substring(1).split(/\//g);
        return ['/'].concat(parts);
      }

      return topix.split(/\//g);
    }
  } else return undefined;
}

export function splitTopicIncludingSeparator(topic: string): string[] {
  const topix = topic;
  return topix.split(/(?<=\/)|(?=\/)/g);
}

export function normalizeTopic(topic: string) {
  let topix = topic;
  if (topix == undefined) topix = '';
  // reduce multiple leading or trailing "/" to just one "/"
  let nt = topix.trim().replace(/(\/{2,}$)|(^\/{2,})/g, '/');
  // do not use starting slashes, see as well https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
  // remove trailing "/" if topic is ends with "#"
  nt = nt.replace(/(#\/$)/g, '#');
  return nt;
}

export function deriveSampleTopicFromTopic(topic: string) {
  let topix = topic;
  if (topix == undefined) topix = '';
  topix = normalizeTopic(topix);
  // replace trailing TOPIC_WILDCARD_MULTI "#" with TOPIC_WILDCARD_SINGLE "*"
  const nt = topic.trim().replace(/#+$/, '+');
  return nt;
}

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

export const TOPIC_WILDCARD_MULTI = '#';
export const TOPIC_WILDCARD_SINGLE = '+';

export function isWildcardTopic(topic: string): boolean {
  const result =
    topic.includes(TOPIC_WILDCARD_MULTI) ||
    topic.includes(TOPIC_WILDCARD_SINGLE);
  return result;
}

export function checkNotSnooping(control: AbstractControl) {
  let errors = {};

  const { snoopStatus } = control['controls'];
  snoopStatus.setErrors(null);

  const isSnoop =
    snoopStatus.value === SnoopStatus.ENABLED ||
    snoopStatus.value === SnoopStatus.STARTED;

  if (isSnoop) {
    errors = {
      Only_One_MuNot_Snooping: {
        message: 'Disable snooping before continuing',
        errorPath: 'snoopStatus'
      }
    };
  }

  return Object.keys(errors).length > 0 ? errors : null;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
// export function checkTopicsInboundAreValidWithOption(options) {
//   return checkTopicsInboundAreValid;
// }

export function checkTopicsInboundAreValid(control: AbstractControl) {
  let errors = {};
  let error: boolean = false;

  // console.log('Validation options:', options);

  const { mappingTopic, mappingTopicSample } =
    control['controls'];
  mappingTopic.setErrors(null);
  mappingTopicSample.setErrors(null);

  // avoid displaying the message error when values are empty
  if (
    mappingTopic.value == '' ||
    mappingTopicSample.value == ''
  ) {
    return { required: false };
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

  // count number of "#" in mappingTopic
  count_multi = (mappingTopic.value.match(/#/g) || []).length;
  // if (count_multi >= 1) {
  if (count_multi > 1) {
    errors = {
      ...errors,
      No_Multi_Level_Wildcard_Allowed_In_MappingTopic: {
        ...ValidationFormlyError[
        'No_Multi_Level_Wildcard_Allowed_In_MappingTopic'
        ],
        errorPath: 'mappingTopic'
      }
    };
  }

  const splitTT: string[] = splitTopicExcludingSeparator(mappingTopic.value, false);
  const splitTTS: string[] = splitTopicExcludingSeparator(
    mappingTopicSample.value, false
  );
  if (splitTT.length != splitTTS.length) {
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
    for (let i = 0; i < splitTT.length; i++) {
      if ('/' == splitTT[i] && !('/' == splitTTS[i])) {
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
      if ('/' == splitTTS[i] && !('/' == splitTT[i])) {
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
        !('/' == splitTT[i]) &&
        !('+' == splitTT[i]) &&
        !('#' == splitTT[i])
      ) {
        if (splitTT[i] != splitTTS[i]) {
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

    // count number of "+" in publishTopic
    const count_single = (publishTopic.value?.match(/\+/g) || []).length;
    if (count_single > 1) {
      errors = {
        ...errors,
        Only_One_Single_Level_Wildcard: {
          ...ValidationFormlyError['Only_One_Single_Level_Wildcard'],
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

  return Object.keys(errors).length > 0 ? errors : null;
}

export function expandExternalTemplate(
  template: object,
  mapping: Mapping,
  levels: string[]
): object {
  if (Array.isArray(template)) {
    return template;
  } else {
    if (mapping.supportsMessageContext) {
      const keys = [CONTEXT_DATA_KEY_NAME];
      return {
        ...template,
        _TOPIC_LEVEL_: levels,
        _CONTEXT_DATA_: keys.reduce((obj, key) => {
          obj[key] = `${key}-sample`;
          return obj;
        }, {})
      };
    } else
      return {
        ...template,
        _TOPIC_LEVEL_: levels
      };
  }
}

export function expandC8YTemplate(template: object, mapping: Mapping): object {
  let result;
  if (mapping.useExternalId) {
    result = {
      ...template,
      _IDENTITY_: {
        // externalIdType: mapping.externalIdType,
        externalId: 'any_SerialNumber',
        // c8ySourceId: '909090'
      }
    };
    if (mapping.direction == Direction.OUTBOUND) {
      result[IDENTITY].c8ySourceId = '909090';
    }
    return result;
  } else {
    result = {
      ...template,
      _IDENTITY_: {
        c8ySourceId: '909090'
      }
    };
    return result;

  }
}

export function randomIdAsString() {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, `${IDENTITY}.c8ySourceId`, identifier);
}

export function reduceSourceTemplate(
  template: object,
  returnPatched: boolean
): string {
  if (!returnPatched) {
    delete template[IDENTITY];
    delete template[TOKEN_TOPIC_LEVEL];
    delete template[TOKEN_CONTEXT_DATA];
  }
  const tt = JSON.stringify(template);
  return tt;
}

export function reduceTargetTemplate(
  template: object,
  patched: boolean
): string {
  if (template && !patched) {
    delete template[IDENTITY];
    delete template[TOKEN_TOPIC_LEVEL];
    delete template[TOKEN_CONTEXT_DATA];
  }
  const tt = JSON.stringify(template);
  return tt;
}

export function getTypeOf(object) {
  const stringConstructor = 'test'.constructor;
  const arrayConstructor = [].constructor;
  const objectConstructor = {}.constructor;
  const booleanConstructor = true.constructor;
  if (object === null) {
    return 'null';
  } else if (object === undefined) {
    return 'undefined';
  } else if (object.constructor === stringConstructor) {
    return 'String';
  } else if (object.constructor === arrayConstructor) {
    return 'Array';
  } else if (object.constructor === objectConstructor) {
    return 'Object';
  } else if (object.constructor === booleanConstructor) {
    return 'Boolean';
  } else if (typeof object === 'number') {
    return 'Number';
  } else {
    return "don't know";
  }
}

export function base64ToString(base64) {
  const binString = atob(base64);
  return new TextDecoder().decode(Uint8Array.from(binString, (m) => m.codePointAt(0)));
}

export function stringToBase64(code2Encode) {
  const bytes = new TextEncoder().encode(code2Encode);
  const binString = Array.from(bytes, (byte: any) =>
    String.fromCodePoint(byte),
  ).join("");
  return btoa(binString);
}

export function base64ToBytes(base64) {
  const binString = atob(base64);
  return Uint8Array.from(binString, (m) => m.codePointAt(0));
}

export function bytesToBase64(bytes) {
  const binString = Array.from(bytes, (byte: any) =>
    String.fromCodePoint(byte),
  ).join("");
  return btoa(binString);
}


