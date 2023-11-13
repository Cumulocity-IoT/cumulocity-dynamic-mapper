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
import { AbstractControl } from "@angular/forms";
import {
  API,
  Direction,
  Mapping,
  MappingSubstitution,
  MappingType,
  ValidationError,
  ValidationFormlyError,
} from "./mapping.model";

export const SAMPLE_TEMPLATES_C8Y = {
  MEASUREMENT: `{                                               
    \"c8y_TemperatureMeasurement\": {
        \"T\": {
            \"value\": 110,
              \"unit\": \"C\" }
          },
      \"time\":\"2022-08-05T00:14:49.389+02:00\",
      \"source\": {
        \"id\":\"909090\" },
      \"type\": \"c8y_TemperatureMeasurement\"
  }`,
  ALARM: `{                                            
    \"source\": {
    \"id\": \"909090\"
    },\
    \"type\": \"c8y_TestAlarm\",
    \"text\": \"This is a new test alarm!\",
    \"severity\": \"MAJOR\",
    \"status\": \"ACTIVE\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\"
  }`,
  EVENT: `{ 
    \"source\": {
    \"id\": \"909090\"
    },
    \"text\": \"This is a new test event.\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\",
    \"type\": \"c8y_TestEvent\"
 }`,
  INVENTORY: `{ 
    \"c8y_IsDevice\": {},
    \"name\": \"Vibration Sensor\",
    \"type\": \"maker_Vibration_Sensor\"
 }`,
  OPERATION: `{ 
   \"deviceId\": \"909090\",
   \"decription\": \"New camera operation!\",
   \"type\": \"maker_Vibration_Sensor\"
}`,
};

export const SAMPLE_TEMPLATES_EXTERNAL = {
  MEASUREMENT: `{                                               
    \"Temperature\": {
        \"value\": 110,
        \"unit\": \"C\" },
      \"time\":\"2022-08-05T00:14:49.389+02:00\",
      \"deviceId\":\"909090\"
  }`,
  ALARM: `{                                            
    \"deviceId\":\"909090\",
    \"alarmType\": \"TestAlarm\",
    \"description\": \"This is a new test alarm!\",
    \"severity\": \"MAJOR\",
    \"status\": \"ACTIVE\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\"
  }`,
  EVENT: `{ 
    \"deviceId\":\"909090\",
    \"description\": \"This is a new test event.\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\",
    \"eventType\": \"TestEvent\"
 }`,
  INVENTORY: `{ 
    \"name\": \"Vibration Sensor\",
    \"type\": \"maker_Vibration_Sensor\",
    \"id\": \"909090\"
 }`,
  OPERATION: `{ 
   \"deviceId\": \"909090\",
   \"decription\": \"New camera operation!\",
   \"type\": \"maker_Vibration_Sensor\"
  }`,
  FLAT_FILE: `{\"message\":\"165, 14.5, \\\"2022-08-06T00:14:50.000+02:00\\\",\\\"c8y_FuelMeasurement\\\"\"}`,
  GENERIC_BINARY: `{\"message\":\"3635 2c20 342e 352c 2022 3230 3232 2d30 382d 3036 5430 303a 3135 3a35 302e 3030 302b 3032 3a30 3022 2c22 6338 795f 4675 656c 4d65 6173 7572 656d 656e 7422 \"\"}`,
};

export const SCHEMA_EVENT = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "EVENT",
  required: ["source", "type", "text", "time"],
  properties: {
    source: {
      $id: "#/properties/source",
      type: "object",
      title: "The managed object to which the event is associated.",
      allOf: [{ required: ["id"] }],
      properties: {
        id: {
          type: "string",
          minLength: 1,
          title: "SourceID",
        },
      },
    },
    type: {
      $id: "#/properties/type",
      type: "string",
      title: "Type of the event.",
    },
    text: {
      $id: "#/properties/text",
      type: "string",
      title: "Text of the event.",
    },
    time: {
      $id: "#/properties/time",
      type: "string",
      title: "Type of the event.",
      pattern:
        "^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$",
    },
  },
};

export const SCHEMA_ALARM = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "ALARM",
  required: ["source", "type", "text", "time", "severity"],
  properties: {
    source: {
      $id: "#/properties/source",
      type: "object",
      title: "The managed object to which the alarm is associated.",
      allOf: [{ required: ["id"] }],
      properties: {
        id: {
          type: "string",
          minLength: 1,
          title: "SourceID",
        },
      },
    },
    type: {
      $id: "#/properties/type",
      type: "string",
      title: "Type of the alarm.",
    },

    severity: {
      $id: "#/properties/severity",
      type: "string",
      title: "Severity of the alarm.",
      pattern: "^((CRITICAL)|(MAJOR)|(MINOR)|(WARNING))$",
    },
    text: {
      $id: "#/properties/text",
      type: "string",
      title: "Text of the alarm.",
    },
    time: {
      $id: "#/properties/time",
      type: "string",
      title: "Type of the alarm.",
      pattern:
        "^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$",
    },
  },
};

export const SCHEMA_MEASUREMENT = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "MEASUREMENT",
  required: ["source", "type", "time"],
  properties: {
    source: {
      $id: "#/properties/source",
      type: "object",
      title: "The managed object to which the measurement is associated.",
      allOf: [{ required: ["id"] }],
      properties: {
        id: {
          type: "string",
          minLength: 1,
          title: "SourceID",
        },
      },
    },
    type: {
      $id: "#/properties/type",
      type: "string",
      title: "Type of the measurement.",
    },
    time: {
      $id: "#/properties/time",
      type: "string",
      title: "Type of the measurement.",
      pattern:
        "^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$",
    },
  },
};

export const SCHEMA_INVENTORY = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "INVENTORY",
  required: ["c8y_IsDevice", "type", "name", "id"],
  properties: {
    c8y_IsDevice: {
      $id: "#/properties/c8y_IsDevice",
      type: "object",
      title: "Mark as device.",
      properties: {},
    },
    type: {
      $id: "#/properties/type",
      type: "string",
      title: "Type of the device.",
    },
    name: {
      $id: "#/properties/name",
      type: "string",
      title: "Name of the device.",
    },
    id: {
      $id: "#/properties/id",
      type: "string",
      title: "Cumulocity id of the device.",
    },
  },
};

export const SCHEMA_OPERATION = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "OPERATION",
  required: ["deviceId"],
  properties: {
    deviceId: {
      $id: "#/properties/deviceId",
      type: "string",
      title:
        "Identifier of the target device where the operation should be performed..",
    },
    description: {
      $id: "#/properties/description",
      type: "string",
      title: "Description of the operation.",
    },
  },
};

export const SCHEMA_PAYLOAD = {
  definitions: {},
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "http://example.com/root.json",
  type: "object",
  title: "PAYLOAD",
  required: [],
};

export const TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
export const TIME = "time";
export const MAPPING_TYPE = "d11r_mapping";
export const PROCESSOR_EXTENSION_TYPE = "d11r_processorExtension";
export const MAPPING_TEST_DEVICE_TYPE = "d11r_testDevice";
export const MAPPING_TEST_DEVICE_FRAGMENT = "d11r_testDevice";

export const MAPPING_FRAGMENT = "d11r_mapping";
export const PATH_OPERATION_ENDPOINT = "operation";
export const PATH_CONFIGURATION_CONNECTION_ENDPOINT =
  "configuration/connector";
export const PATH_CONFIGURATION_SERVICE_ENDPOINT = "configuration/service";
export const PATH_MAPPING_TREE_ENDPOINT = "monitoring/tree";
export const PATH_MAPPING_ACTIVE_SUBSCRIPTIONS_ENDPOINT = "monitoring/tree";
export const PATH_STATUS_CONNECTOR_ENDPOINT = "monitoring/status/connectors";
export const PATH_FEATURE_ENDPOINT = "feature";
export const PATH_EXTENSION_ENDPOINT = "extension";
export const PATH_SUBSCRIPTION_ENDPOINT = "subscription";
export const PATH_SUBSCRIPTIONS_ENDPOINT = "subscriptions";
export const PATH_MAPPING_ENDPOINT = "mapping";
export const BASE_URL = "service/mqtt-mapping-service";
export const AGENT_ID = "d11r_mappingService";
export const COLOR_HIGHLIGHTED: string = "lightgrey"; //#5FAEEC';

export const CONNECTOR_STATUS_FRAGMENT = "d11r_connectorStatus";
export const MAPPING_STATUS_FRAGMENT = "d11r_mappingStatus";

export function getExternalTemplate(mapping: Mapping): any {
  if (
    mapping.mappingType == MappingType.FLAT_FILE ||
    mapping.mappingType == MappingType.GENERIC_BINARY
  ) {
    return SAMPLE_TEMPLATES_EXTERNAL[mapping.mappingType];
  } else {
    return SAMPLE_TEMPLATES_EXTERNAL[mapping.targetAPI];
  }
}
export function getSchema(
  targetAPI: string,
  direction: Direction,
  target: boolean
): any {
  if (
    (target && (!direction || direction == Direction.INBOUND)) ||
    (!target && direction == Direction.OUTBOUND)
  ) {
    if (targetAPI == API.ALARM.name) {
      return SCHEMA_ALARM;
    } else if (targetAPI == API.EVENT.name) {
      return SCHEMA_EVENT;
    } else if (targetAPI == API.MEASUREMENT.name) {
      return SCHEMA_MEASUREMENT;
    } else if (targetAPI == API.INVENTORY.name) {
      return SCHEMA_INVENTORY;
    } else {
      return SCHEMA_OPERATION;
    }
  } else {
    return SCHEMA_PAYLOAD;
  }
}

/*
 * for '/device/hamburg/temperature/' return ["/", "device", "/", "hamburg", "/", "temperature", "/"]
 */
export function splitTopicExcludingSeparator(topic: string): string[] {
  topic = topic.trim().replace(/(\/{1,}$)|(^\/{1,})/g, "");
  return topic.split(/\//g);
}

export function splitTopicIncludingSeparator(topic: string): string[] {
  return topic.split(/(?<=\/)|(?=\/)/g);
}

export function normalizeTopic(topic: string) {
  if (topic == undefined) topic = "";
  // reduce multiple leading or trailing "/" to just one "/"
  let nt = topic.trim().replace(/(\/{2,}$)|(^\/{2,})/g, "/");
  // do not use starting slashes, see as well https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
  // remove trailing "/" if topic is ends with "#"
  nt = nt.replace(/(#\/$)/g, "#");
  return nt;
}

export function deriveTemplateTopicFromTopic(topic: string) {
  if (topic == undefined) topic = "";
  topic = normalizeTopic(topic);
  // replace trailing TOPIC_WILDCARD_MULTI "#" with TOPIC_WILDCARD_SINGLE "*"
  let nt = topic.trim().replace(/\#+$/, "+");
  return nt;
}

export function isTopicNameValid(topic: string): any {
  topic = normalizeTopic(topic);

  let errors = {};
  // count number of "#"
  let count_multi = (topic.match(/\#/g) || []).length;
  if (count_multi > 1)
    errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  let count_single = (topic.match(/\+/g) || []).length;
  if (count_single > 1)
    errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (
    count_multi >= 1 &&
    topic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topic.length
  )
    errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  return errors;
}

export function isTemplateTopicValid(topic: string): any {
  // templateTopic can contain any number of "+" TOPIC_WILDCARD_SINGLE but no "#"
  // TOPIC_WILDCARD_MULTI
  topic = normalizeTopic(topic);

  // let errors = {};
  // // count number of "#"
  // let count_multi = (topic.match(/\#/g) || []).length;
  // if (count_multi > 1) errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // // count number of "+"
  // let count_single = (topic.match(/\+/g) || []).length;
  // if (count_single > 1) errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  // if (count_multi >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topic.length) errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  let errors = {};
  // count number of "#"
  let count_multi = (topic.match(/\#/g) || []).length;
  if (count_multi >= 1)
    errors[ValidationError.No_Multi_Level_Wildcard_Allowed_In_TemplateTopic] =
      true;

  return errors;
}

export function isSubscriptionTopicValid(topic: string): any {
  topic = normalizeTopic(topic);

  let errors = {};
  // count number of "#"
  let count_multi = (topic.match(/\#/g) || []).length;
  if (count_multi > 1)
    errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  let count_single = (topic.match(/\+/g) || []).length;
  if (count_single > 1)
    errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (
    count_multi >= 1 &&
    topic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topic.length
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

export function isTemplateTopicUnique(
  mapping: Mapping,
  mappings: Mapping[]
): boolean {
  let result = true;
  // result = mappings.every(m => {
  //   return ((!mapping.templateTopic.startsWith(m.templateTopic) && !m.templateTopic.startsWith(mapping.templateTopic)) || mapping.id == m.id);
  // })
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

export const TOPIC_WILDCARD_MULTI = "#";
export const TOPIC_WILDCARD_SINGLE = "+";

export function isWildcardTopic(topic: string): boolean {
  const result =
    topic.includes(TOPIC_WILDCARD_MULTI) ||
    topic.includes(TOPIC_WILDCARD_SINGLE);
  return result;
}

export function isSubstituionValid(mapping: Mapping): boolean {
  let count = mapping.substitutions
    .filter((sub) =>
      definesDeviceIdentifier(mapping.targetAPI, sub, mapping.direction)
    )
    .map((m) => 1)
    .reduce(
      (
        previousValue: number,
        currentValue: number,
        currentIndex: number,
        array: number[]
      ) => {
        return previousValue + currentValue;
      },
      0
    );
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
//         errorPath: 'templateTopic'
//       }
//     };
//   }
//   if (count < 1) {
//     errors[ValidationError.One_Substitution_Defining_Device_Identifier_Must_Be_Used] = true
//   }
// } else {
// }
//console.log(stepperConfiguration, mapping.mappingType)
//   //console.log("Tested substitutions:", count, errors, mapping.substitutions, mapping.substitutions.filter(m => m.definesIdentifier));
//   return Object.keys(errors).length > 0 ? errors : null;
// }

export function countDeviceIdentifiers(mapping: Mapping): number {
  return mapping.substitutions.filter((sub) =>
    definesDeviceIdentifier(mapping.targetAPI, sub, mapping.direction)
  ).length;
}

export function checkTopicsInboundAreValid(control: AbstractControl) {
  let errors = {};
  let error: boolean = false;

  const { templateTopic, templateTopicSample, subscriptionTopic } =
    control["controls"];
  templateTopic.setErrors(null);
  templateTopicSample.setErrors(null);
  subscriptionTopic.setErrors(null);

  // avoid displaying the message error when values are empty
  if (
    templateTopic.value == "" ||
    templateTopicSample.value == "" ||
    subscriptionTopic.value == ""
  ) {
    return { required: false };
  }

  // in the topic a multi level wildcard "*" can appear and is replaced by a single level wildcard "+"
  // for comparison the "#" must then be replaced by a "+"
  // allowed (tt=template topic, st= subscription topic)
  // allowed    st                      tt
  //    +       /topic/                 /topic/
  //    -       /topic/                 /topic/value
  //    +       /topic/#                /topic/value
  //    +       /topic/+                /topic/value
  //    -       /topic/+                /topic/important/value
  //    +       /topic/+/value          /topic/important/value
  //    +       device/#                device/+/rom/

  //let f = (tt, st) => new RegExp(st.split`+`.join`[^/]+`.split`#`.join`.*`).test(tt)
  //error = !f(subscriptionTopic, templateTopic);
  let f = (t) => (s) =>
    new RegExp(
      s.concat("@").split("+").join("[^/]+").split("#").join(".+")
    ).test(t.concat("@"));
  error = !f(templateTopic.value)(subscriptionTopic.value);
  if (error) {
    errors = {
      ...errors,
      TemplateTopic_Must_Match_The_SubscriptionTopic: {
        ...ValidationFormlyError[
          "TemplateTopic_Must_Match_The_SubscriptionTopic"
        ],
        errorPath: "templateTopic",
      },
    };
  }

  // count number of "#" in subscriptionTopic
  let count_multi = (subscriptionTopic.value.match(/\#/g) || []).length;
  if (count_multi > 1) {
    errors = {
      ...errors,
      Only_One_Multi_Level_Wildcard: {
        ...ValidationFormlyError["Only_One_Multi_Level_Wildcard"],
        errorPath: "subscriptionTopic",
      },
    };
  }

  // count number of "+" in subscriptionTopic
  let count_single = (subscriptionTopic.value.match(/\+/g) || []).length;
  if (count_single > 1) {
    errors = {
      ...errors,
      Only_One_Single_Level_Wildcard: {
        ...ValidationFormlyError["Only_One_Single_Level_Wildcard"],
        errorPath: "subscriptionTopic",
      },
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
        ...ValidationFormlyError["Multi_Level_Wildcard_Only_At_End"],
        errorPath: "subscriptionTopic",
      },
    };
  }

  // count number of "#" in templateTopic
  count_multi = (templateTopic.value.match(/\#/g) || []).length;
  if (count_multi >= 1) {
    errors = {
      ...errors,
      No_Multi_Level_Wildcard_Allowed_In_TemplateTopic: {
        ...ValidationFormlyError[
          "No_Multi_Level_Wildcard_Allowed_In_TemplateTopic"
        ],
        errorPath: "templateTopic",
      },
    };
  }

  let splitTT: String[] = splitTopicExcludingSeparator(templateTopic.value);
  let splitTTS: String[] = splitTopicExcludingSeparator(
    templateTopicSample.value
  );
  if (splitTT.length != splitTTS.length) {
    errors = {
      ...errors,
      TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
        {
          ...ValidationFormlyError[
            "TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name"
          ],
          errorPath: "templateTopicSample",
        },
    };
  } else {
    for (let i = 0; i < splitTT.length; i++) {
      if ("/" == splitTT[i] && !("/" == splitTTS[i])) {
        errors = {
          ...errors,
          TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
                "TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
              ],
              errorPath: "templateTopicSample",
            },
        };
        break;
      }
      if ("/" == splitTTS[i] && !("/" == splitTT[i])) {
        errors = {
          ...errors,
          TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
                "TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
              ],
              errorPath: "templateTopicSample",
            },
        };
        break;
      }
      if (
        !("/" == splitTT[i]) &&
        !("+" == splitTT[i]) &&
        !("#" == splitTT[i])
      ) {
        if (splitTT[i] != splitTTS[i]) {
          errors = {
            ...errors,
            TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
              {
                ...ValidationFormlyError[
                  "TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
                ],
                errorPath: "templateTopicSample",
              },
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

  const { publishTopic, templateTopicSample } = control["controls"];
  publishTopic.setErrors(null);
  templateTopicSample.setErrors(null);

  // avoid displaying the message error when values are empty
  if (publishTopic.value == "" || templateTopicSample.value == "") {
    return null;
  }

  // count number of "#" in publishTopic
  let count_multi = (publishTopic.value.match(/\#/g) || []).length;
  if (count_multi > 1) {
    errors = {
      ...errors,
      Only_One_Multi_Level_Wildcard: {
        ...ValidationFormlyError["Only_One_Multi_Level_Wildcard"],
        errorPath: "publishTopic",
      },
    };
  }

  // count number of "+" in publishTopic
  let count_single = (publishTopic.value.match(/\+/g) || []).length;
  if (count_single > 1) {
    errors = {
      ...errors,
      Only_One_Single_Level_Wildcard: {
        ...ValidationFormlyError["Only_One_Single_Level_Wildcard"],
        errorPath: "publishTopic",
      },
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
        ...ValidationFormlyError["Multi_Level_Wildcard_Only_At_End"],
        errorPath: "publishTopic",
      },
    };
  }

  let splitPT: String[] = splitTopicExcludingSeparator(publishTopic.value);
  let splitTTS: String[] = splitTopicExcludingSeparator(
    templateTopicSample.value
  );
  if (splitPT.length != splitTTS.length) {
    errors = {
      ...errors,
      PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
        {
          ...ValidationFormlyError[
            "PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name"
          ],
          errorPath: "templateTopicSample",
        },
    };
  } else {
    for (let i = 0; i < splitPT.length; i++) {
      if ("/" == splitPT[i] && !("/" == splitTTS[i])) {
        errors = {
          ...errors,
          PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
                "PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
              ],
              errorPath: "templateTopicSample",
            },
        };
        break;
      }
      if ("/" == splitTTS[i] && !("/" == splitPT[i])) {
        errors = {
          ...errors,
          PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
            {
              ...ValidationFormlyError[
                "PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
              ],
              errorPath: "templateTopicSample",
            },
        };
        break;
      }
      if (
        !("/" == splitPT[i]) &&
        !("+" == splitPT[i]) &&
        !("#" == splitPT[i])
      ) {
        if (splitPT[i] != splitTTS[i]) {
          errors = {
            ...errors,
            PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
              {
                ...ValidationFormlyError[
                  "PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name"
                ],
                errorPath: "templateTopicSample",
              },
          };
          break;
        }
      }
    }
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

export function whatIsIt(object) {
  var stringConstructor = "test".constructor;
  var arrayConstructor = [].constructor;
  var objectConstructor = {}.constructor;
  if (object === null) {
    return "null";
  } else if (object === undefined) {
    return "undefined";
  } else if (object.constructor === stringConstructor) {
    return "String";
  } else if (object.constructor === arrayConstructor) {
    return "Array";
  } else if (object.constructor === objectConstructor) {
    return "Object";
  } else if (typeof object === "number") {
    return "number";
  } else {
    return "don't know";
  }
}

export const isNumeric = (num: any) =>
  (typeof num === "number" || (typeof num === "string" && num.trim() !== "")) &&
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
    resolve2ExternalId: sub.resolve2ExternalId,
  };
}

export function expandExternalTemplate(
  t: object,
  m: Mapping,
  levels: String[]
): object {
  if (Array.isArray(t)) {
    return t;
  } else {
    return {
      ...t,
      _TOPIC_LEVEL_: levels,
    };
  }
}

export function expandC8YTemplate(t: object, m: Mapping): object {
  if (m.targetAPI == API.INVENTORY.name) {
    return {
      ...t,
      id: "909090",
    };
  } else {
    return t;
  }
}

export function reduceSourceTemplate(t: object, patched: boolean): string {
  if (!patched) delete t[TOKEN_TOPIC_LEVEL];
  let tt = JSON.stringify(t);
  return tt;
}

export function reduceTargetTemplate(t: object, patched: boolean): string {
  let tt = JSON.stringify(t);
  return tt;
}