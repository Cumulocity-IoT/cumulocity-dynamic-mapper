/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import {
  API,
  Direction,
  Mapping,
  MappingSubstitution,
  SnoopStatus
} from '../../shared';
import {
  SubstituteValue,
  SubstituteValueType
} from '../processor/processor.model';
import { ValidationError, ValidationFormlyError } from './mapping.model';

export function getTypedValue(subValue: SubstituteValue): any {
  if (subValue.type == SubstituteValueType.NUMBER) {
    return Number(subValue.value);
  } else if (subValue.type == SubstituteValueType.TEXTUAL) {
    return String(subValue.value);
  } else {
    return subValue.value;
  }
}

export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const TIME = 'time';
/*
 * for '/device/hamburg/temperature/' return ["/", "device", "/", "hamburg", "/", "temperature", "/"]
 */
export function splitTopicExcludingSeparator(topic: string): string[] {
  let topix = topic;
  topix = topix.trim().replace(/(\/{1,}$)|(^\/{1,})/g, '');
  return topix.split(/\//g);
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

export function deriveMappingTopicFromTopic(topic: string) {
  let topix = topic;
  if (topix == undefined) topix = '';
  topix = normalizeTopic(topix);
  // replace trailing TOPIC_WILDCARD_MULTI "#" with TOPIC_WILDCARD_SINGLE "*"
  const nt = topic.trim().replace(/#+$/, '+');
  return nt;
}

export function isTopicNameValid(topic: string): any {
  let topix = topic;
  topix = normalizeTopic(topix);
  const errors = {};
  // count number of "#"
  const count_multi = (topix.match(/#/g) || []).length;
  if (count_multi > 1)
    errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  const count_single = (topix.match(/\+/g) || []).length;
  if (count_single > 1)
    errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (
    count_multi >= 1 &&
    topix.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topix.length
  )
    errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  return errors;
}

export function isMappingTopicValid(topic: string): any {
  let topix = topic;
  // mappingTopic can contain any number of "+" TOPIC_WILDCARD_SINGLE but no "#"
  // TOPIC_WILDCARD_MULTI
  topix = normalizeTopic(topix);

  // let errors = {};
  // // count number of "#"
  // let count_multi = (topix.match(/\#/g) || []).length;
  // if (count_multi > 1) errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // // count number of "+"
  // let count_single = (topix.match(/\+/g) || []).length;
  // if (count_single > 1) errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  // if (count_multi >= 1 && topix.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topix.length) errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  const errors = {};
  // count number of "#"
  const count_multi = (topix.match(/#/g) || []).length;
  if (count_multi >= 1)
    errors[ValidationError.No_Multi_Level_Wildcard_Allowed_In_MappingTopic] =
      true;

  return errors;
}

export function isSubscriptionTopicValid(topic: string): any {
  let topix = topic;
  topix = normalizeTopic(topix);
  const errors = {};
  // count number of "#"
  const count_multi = (topix.match(/#/g) || []).length;
  if (count_multi > 1)
    errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  const count_single = (topix.match(/\+/g) || []).length;
  if (count_single > 1)
    errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (
    count_multi >= 1 &&
    topix.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topix.length
  )
    errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  return errors;
}

export function isSubscriptionTopicUnique(
  mapping: Mapping,
  mappings: Mapping[]
): boolean {
  let result = true;
  result = mappings.every((m) => {
    return (
      (!mapping.subscriptionTopic.startsWith(m.subscriptionTopic) &&
        !m.subscriptionTopic.startsWith(mapping.subscriptionTopic)) ||
      mapping.id == m.id
    );
  });
  return result;
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
    return mapping.filterOutbound != m.filterOutbound || mapping.id == m.id;
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

export function isSubstitutionValid(mapping: Mapping): boolean {
  const count = mapping.substitutions
    .filter((sub) =>
      definesDeviceIdentifier(mapping.targetAPI, sub, mapping.direction)
    )
    .map(() => 1)
    .reduce((previousValue: number, currentValue: number) => {
      return previousValue + currentValue;
    }, 0);
  return (
    (mapping.direction != Direction.OUTBOUND && count == 1) ||
    mapping.direction == Direction.OUTBOUND
  );
}

// export function checkSubstitutionIsValid(control: AbstractControl) {
//   let errors = {};
// let count = mapping.substitutions.filter(sub => definesDeviceIdentifier(mapping.targetAPI, sub)).map(m => 1).reduce((previousValue: number, currentValue: number, currentIndex: number, array: number[]) => {
//   return previousValue + currentValue;
// }, 0)

// let count = countDeviceIdentifiers(mapping);

// if (!stepperConfiguration.allowNoDefinedIdentifier && mapping.direction != Direction.OUTBOUND) {
//   if (count > 1) {
//     errors = {
//       ...errors,
//       Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used: {
//         ...ValidationFormlyError['Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used'],
//         errorPath: 'mappingTopic'
//       }
//     };
//   }
//   if (count < 1) {
//     errors[ValidationError.One_Substitution_Defining_Device_Identifier_Must_Be_Used] = true
//   }
// } else {
// }
// console.log(stepperConfiguration, mapping.mappingType)
//   //console.log("Tested substitutions:", count, errors, mapping.substitutions, mapping.substitutions.filter(m => m.definesIdentifier));
//   return Object.keys(errors).length > 0 ? errors : null;
// }

export function countDeviceIdentifiers(mapping: Mapping): number {
  return mapping.substitutions.filter((sub) =>
    definesDeviceIdentifier(mapping.targetAPI, sub, mapping.direction)
  ).length;
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
export function checkTopicsInboundAreValidWithOption(options) {
  return checkTopicsInboundAreValid;

  function checkTopicsInboundAreValid(control: AbstractControl) {
    let errors = {};
    let error: boolean = false;

    // console.log('Validation options:', options);

    const { mappingTopic, mappingTopicSample, subscriptionTopic } =
      control['controls'];
    mappingTopic.setErrors(null);
    mappingTopicSample.setErrors(null);
    subscriptionTopic.setErrors(null);

    // avoid displaying the message error when values are empty
    if (
      mappingTopic.value == '' ||
      mappingTopicSample.value == '' ||
      subscriptionTopic.value == ''
    ) {
      return { required: false };
    }

    // in the topic a multi level wildcard "*" can appear and is replaced by a single level wildcard "+"
    // for comparison the "#" must then be replaced by a "+"
    // allowed (mt=template topic, st= subscription topic)
    // allowed    st                      mt
    //    +       /topic/                 /topic/
    //    -       /topic/                 /topic/value
    //    +       /topic/#                /topic/value
    //    +       /topic/+                /topic/value
    //    -       /topic/+                /topic/important/value
    //    +       /topic/+/value          /topic/important/value
    //    +       device/#                device/+/rom/

    // let f = (tt, st) => new RegExp(st.split`+`.join`[^/]+`.split`#`.join`.*`).test(tt)
    // error = !f(subscriptionTopic, mappingTopic);
    const f = (t) => (s) =>
      new RegExp(
        s.concat('@').split('+').join('[^/]+').split('#').join('.+')
      ).test(t.concat('@'));
    error = !f(mappingTopic.value)(subscriptionTopic.value);
    if (error) {
      errors = {
        ...errors,
        MappingTopic_Must_Match_The_SubscriptionTopic: {
          ...ValidationFormlyError[
            'MappingTopic_Must_Match_The_SubscriptionTopic'
          ],
          errorPath: 'subscriptionTopic'
        }
      };
    }

    // count number of "#" in subscriptionTopic
    let count_multi = (subscriptionTopic.value.match(/#/g) || []).length;
    if (count_multi > 1) {
      errors = {
        ...errors,
        Only_One_Multi_Level_Wildcard: {
          ...ValidationFormlyError['Only_One_Multi_Level_Wildcard'],
          errorPath: 'subscriptionTopic'
        }
      };
    }

    // count number of "+" in subscriptionTopic
    const count_single = (subscriptionTopic.value.match(/\+/g) || []).length;
    if (count_single > 1) {
      errors = {
        ...errors,
        Only_One_Single_Level_Wildcard: {
          ...ValidationFormlyError['Only_One_Single_Level_Wildcard'],
          errorPath: 'subscriptionTopic'
        }
      };
    }

    // wildcard "#" can only appear at the end in subscriptionTopic
    if (
      count_multi >= 1 &&
      subscriptionTopic.value.indexOf(TOPIC_WILDCARD_MULTI) + 1 !=
        subscriptionTopic.value.length
    ) {
      errors = {
        ...errors,
        Multi_Level_Wildcard_Only_At_End: {
          ...ValidationFormlyError['Multi_Level_Wildcard_Only_At_End'],
          errorPath: 'subscriptionTopic'
        }
      };
    }

    // count number of "#" in mappingTopic
    count_multi = (mappingTopic.value.match(/#/g) || []).length;
    if (count_multi >= 1) {
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

    const splitTT: string[] = splitTopicExcludingSeparator(mappingTopic.value);
    const splitTTS: string[] = splitTopicExcludingSeparator(
      mappingTopicSample.value
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
}

export function checkTopicsOutboundAreValid(control: AbstractControl) {
  let errors = {};

  const { publishTopic, publishTopicSample } = control['controls'];
  publishTopic.setErrors(null);
  publishTopicSample.setErrors(null);

  // avoid displaying the message error when values are empty
  if (publishTopic.value == '' || publishTopicSample.value == '') {
    return null;
  }

  // count number of "#" in publishTopic
  const count_multi = (publishTopic.value.match(/#/g) || []).length;
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
  const count_single = (publishTopic.value.match(/\+/g) || []).length;
  if (count_single > 1) {
    errors = {
      ...errors,
      Only_One_Single_Level_Wildcard: {
        ...ValidationFormlyError['Only_One_Single_Level_Wildcard'],
        errorPath: 'publishTopic'
      }
    };
  }

  // wildcard "#" can only appear at the end in subscriptionTopic
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

  const splitPT: string[] = splitTopicExcludingSeparator(publishTopic.value);
  const splitTTS: string[] = splitTopicExcludingSeparator(
    publishTopicSample.value
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
  return Object.keys(errors).length > 0 ? errors : null;
}

export const isNumeric = (num: any) =>
  (typeof num === 'number' || (typeof num === 'string' && num.trim() !== '')) &&
  !isNaN(num as number);

export function definesDeviceIdentifier(
  api: string,
  sub: MappingSubstitution,
  direction: Direction
): boolean {
  if (direction == Direction.INBOUND) {
    return sub?.pathTarget == API[api].identifier;
  } else {
    return sub?.pathSource == API[api].identifier;
  }
}

export function findDeviceIdentifier(mapping: Mapping): MappingSubstitution {
  const mp = mapping.substitutions.filter((sub) =>
    definesDeviceIdentifier(mapping.targetAPI, sub, mapping.direction)
  );
  if (mp && mp.length > 0) {
    return mp[0];
  } else {
    return null;
  }
}

export function cloneSubstitution(
  sub: MappingSubstitution
): MappingSubstitution {
  return {
    pathSource: sub.pathSource,
    pathTarget: sub.pathTarget,
    repairStrategy: sub.repairStrategy,
    expandArray: sub.expandArray,
    resolve2ExternalId: sub.resolve2ExternalId
  };
}

export function expandExternalTemplate(
  template: object,
  mapping: Mapping,
  levels: string[]
): object {
  if (Array.isArray(template)) {
    return template;
  } else {
    // if (mapping.messageContextKeys) {
    //     const keys = mapping.messageContextKeys.split(',').map(function (item) {
    //       return item.trim();
    //     });
    //     return {
    //       ...template,
    //       _TOPIC_LEVEL_: levels,
    //       _CONTEXT_DATA_: keys.reduce((obj, key) => {
    //         obj[key] = `${key}-sample`;
    //         return obj;
    //       }, {})
    //     };
    //   }
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
  if (mapping.targetAPI == API.INVENTORY.name) {
    return {
      ...template,
      id: '909090'
    };
  } else {
    return template;
  }
}

export function reduceSourceTemplate(
  template: object,
  patched: boolean
): string {
  if (!patched) {
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
    delete template[TOKEN_TOPIC_LEVEL];
    delete template[TOKEN_CONTEXT_DATA];
  }
  const tt = JSON.stringify(template);
  return tt;
}

export function isDisabled(condition: boolean) {
  return condition ? '' : null;
}
