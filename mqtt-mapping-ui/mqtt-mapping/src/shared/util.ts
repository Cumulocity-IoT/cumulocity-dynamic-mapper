import { AbstractControl, ValidationErrors, ValidatorFn } from "@angular/forms"
import { StepperConfiguration } from "src/mqtt-mapping/stepper/stepper-model"
import { API, Mapping, MappingSubstitution, MappingType, ValidationError } from "./mapping.model"

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
}`
}

export const SCHEMA_EVENT = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'EVENT',
  'required': [
    'source',
    'type',
    'text',
    'time'
  ],
  'properties': {
    'source': {
      '$id': '#/properties/source',
      'type': 'object',
      'title': 'The managed object to which the event is associated.',
      "allOf": [
        { "required": ["id"] },
      ],
      'properties': {
        'id': {
          'type': 'string',
          'minLength': 1,
          'title': 'SourceID'
        }
      }
    },
    'type': {
      '$id': '#/properties/type',
      'type': 'string',
      'title': 'Type of the event.',
    },
    'text': {
      '$id': '#/properties/text',
      'type': 'string',
      'title': 'Text of the event.',
    },
    'time': {
      '$id': '#/properties/time',
      'type': 'string',
      'title': 'Type of the event.',
      'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
    }
  }
}

export const SCHEMA_ALARM = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'ALARM',
  'required': [
    'source',
    'type',
    'text',
    'time',
    'severity'
  ],
  'properties': {
    'source': {
      '$id': '#/properties/source',
      'type': 'object',
      'title': 'The managed object to which the alarm is associated.',
      "allOf": [
        { "required": ["id"] },
      ],
      'properties': {
        'id': {
          'type': 'string',
          'minLength': 1,
          'title': 'SourceID'
        }
      }
    },
    'type': {
      '$id': '#/properties/type',
      'type': 'string',
      'title': 'Type of the alarm.',
    },

    'severity': {
      '$id': '#/properties/severity',
      'type': 'string',
      'title': 'Severity of the alarm.',
      'pattern': '^((CRITICAL)|(MAJOR)|(MINOR)|(WARNING))$'
    },
    'text': {
      '$id': '#/properties/text',
      'type': 'string',
      'title': 'Text of the alarm.',
    },
    'time': {
      '$id': '#/properties/time',
      'type': 'string',
      'title': 'Type of the alarm.',
      'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
    }
  }
}

export const SCHEMA_MEASUREMENT = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'MEASUREMENT',
  'required': [
    'source',
    'type',
    'time',
  ],
  'properties': {
    'source': {
      '$id': '#/properties/source',
      'type': 'object',
      'title': 'The managed object to which the measurement is associated.',
      "allOf": [
        { "required": ["id"] },
      ],
      'properties': {
        'id': {
          'type': 'string',
          'minLength': 1,
          'title': 'SourceID'
        }
      }
    },
    'type': {
      '$id': '#/properties/type',
      'type': 'string',
      'title': 'Type of the measurement.',
    },
    'time': {
      '$id': '#/properties/time',
      'type': 'string',
      'title': 'Type of the measurement.',
      'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
    }
  }
}

export const SCHEMA_INVENTORY = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'INVENTORY',
  'required': [
    'c8y_IsDevice',
    'type',
    'name',
  ],
  'properties': {
    'c8y_IsDevice': {
      '$id': '#/properties/c8y_IsDevice',
      'type': 'object',
      'title': 'Mark as device.',
      'properties': {
      }
    },
    'type': {
      '$id': '#/properties/type',
      'type': 'string',
      'title': 'Type of the device.',
    },
    'name': {
      '$id': '#/properties/name',
      'type': 'string',
      'title': 'Name of the device.',
    }
  }
}

export const SCHEMA_OPERATION = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'OPERATION',
  'required': [
    'deviceId',
  ],
  'properties': {
    'deviceId': {
      '$id': '#/properties/deviceId',
      'type': 'string',
      'title': 'Identifier of the target device where the operation should be performed..',
    },
    'description': {
      '$id': '#/properties/description',
      'type': 'string',
      'title': 'Description of the operation.',
    },
  }
}

export const SCHEMA_PAYLOAD = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'PAYLOAD',
  'required': [
  ],
}

export const TOKEN_DEVICE_TOPIC = "_DEVICE_IDENT_";
export const TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
export const TIME = "time";

export const MAPPING_TYPE = 'c8y_mqttMapping';
export const PROCESSOR_EXTENSION_TYPE = 'c8y_mqttMapping_Extension';
export const MQTT_TEST_DEVICE_TYPE = 'c8y_mqttMapping_TestDevice';
export const MQTT_TEST_DEVICE_FRAGMENT = 'c8y_mqttMapping_TestDevice';
export const MQTT_MAPPING_GENERATED_TEST_DEVICE = "c8y_mqttMapping_Generated_Type";
export const STATUS_MAPPING_EVENT_TYPE = "mqtt_mapping_event";
export const STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";
export const MAPPING_FRAGMENT = 'c8y_mqttMapping';
export const PATH_OPERATION_ENDPOINT = 'operation';
export const PATH_CONFIGURATION_CONNECTION_ENDPOINT = 'configuration/connection';
export const PATH_EXTERNSION_ENDPOINT = 'extension';
export const PATH_CONFIGURATION_SERVICE_ENDPOINT = 'configuration/service';
export const PATH_MAPPING_TREE_ENDPOINT = 'tree';
export const PATH_STATUS_ENDPOINT = 'status/service';
export const PATH_MONITORING_ENDPOINT = 'monitor-websocket';
export const BASE_URL = 'service/mqtt-mapping-service';
export const AGENT_ID = 'MQTT_MAPPING_SERVICE';
export const MQTT_TEST_DEVICE_ID = 'MQTT_MAPPING_TEST_DEVICE';
export const COLOR_HIGHLIGHTED: string = 'lightgrey'; //#5FAEEC';

export function getSchema(targetAPI: string): any {
  if (targetAPI == API.ALARM.name) {
    return SCHEMA_ALARM;
  } else if (targetAPI == API.EVENT.name) {
    return SCHEMA_EVENT;
  } else if (targetAPI == API.MEASUREMENT.name) {
    return SCHEMA_MEASUREMENT;
  } else if ((targetAPI == API.INVENTORY.name)) {
    return SCHEMA_INVENTORY;
  } else {
    return SCHEMA_OPERATION;
  }
}

/*
* for '/device/hamburg/temperature/' return ["/", "device", "/", "hamburg", "/", "temperature", "/"]
*/
export function splitTopicExcludingSeparator(topic: string): string[] {
  topic = topic.trim().replace(/(\/{1,}$)|(^\/{1,})/g, '');
  return topic.split(/\//g);
}

export function splitTopicIncludingSeparator(topic: string): string[] {
  return topic.split(/(?<=\/)|(?=\/)/g);
}

export function normalizeTopic(topic: string) {
  if (topic == undefined) topic = '';
  // reduce multiple leading or trailing "/" to just one "/"
  let nt = topic.trim().replace(/(\/{2,}$)|(^\/{2,})/g, '/');
  // do not use starting slashes, see as well https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
  // remove trailing "/" if topic is ends with "#"
  nt = nt.replace(/(#\/$)/g, "#");
  return nt
}

export function deriveTemplateTopicFromTopic(topic: string) {
  if (topic == undefined) topic = '';
  topic = normalizeTopic(topic)
  // replace trailing TOPIC_WILDCARD_MULTI "#" with TOPIC_WILDCARD_SINGLE "*" 
  let nt = topic.trim().replace(/\#+$/, '+')
  return nt
}

export function isTopicNameValid(topic: string): any {
  topic = normalizeTopic(topic);

  let errors = {};
  // count number of "#"
  let count_multi = (topic.match(/\#/g) || []).length;
  if (count_multi > 1) errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  let count_single = (topic.match(/\+/g) || []).length;
  if (count_single > 1) errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (count_multi >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topic.length) errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

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
  if (count_multi >= 1) errors[ValidationError.No_Multi_Level_Wildcard_Allowed_In_TemplateTopic] = true;

  return errors;
}

export function isSubscriptionTopicValid(topic: string): any {
  topic = normalizeTopic(topic);

  let errors = {};
  // count number of "#"
  let count_multi = (topic.match(/\#/g) || []).length;
  if (count_multi > 1) errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  let count_single = (topic.match(/\+/g) || []).length;
  if (count_single > 1) errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (count_multi >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != topic.length) errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  return errors;
}

export function isSubscriptionTopicUnique(mapping: Mapping, mappings: Mapping[]): boolean {
  let result = true;
  result = mappings.every(m => {
    return ((!mapping.subscriptionTopic.startsWith(m.subscriptionTopic) && !m.subscriptionTopic.startsWith(mapping.subscriptionTopic)) || mapping.id == m.id);
  })
  return result;
}

export function isTemplateTopicUnique(mapping: Mapping, mappings: Mapping[]): boolean {
  let result = true;
  // result = mappings.every(m => {
  //   return ((!mapping.templateTopic.startsWith(m.templateTopic) && !m.templateTopic.startsWith(mapping.templateTopic)) || mapping.id == m.id);
  // })
  return result;
}

export const TOPIC_WILDCARD_MULTI = "#"
export const TOPIC_WILDCARD_SINGLE = "+"

export function isWildcardTopic(topic: string): boolean {
  const result = topic.includes(TOPIC_WILDCARD_MULTI) || topic.includes(TOPIC_WILDCARD_SINGLE);
  return result;
}

export function isSubstituionValid(mapping: Mapping): boolean {
  let count = mapping.substitutions.filter(sub => definesDeviceIdentifier(mapping.targetAPI, sub)).map(m => 1).reduce((previousValue: number, currentValue: number, currentIndex: number, array: number[]) => {
    return previousValue + currentValue;
  }, 0)
  return (count > 1);
}

export function checkSubstitutionIsValid(mapping: Mapping, stepperConfiguration: StepperConfiguration): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const errors = {}
    let defined = false

    // let count = mapping.substitutions.filter(sub => definesDeviceIdentifier(mapping.targetAPI, sub)).map(m => 1).reduce((previousValue: number, currentValue: number, currentIndex: number, array: number[]) => {
    //   return previousValue + currentValue;
    // }, 0)

    let count = countDeviceIdentifiers(mapping);
    if (count > 1 && !stepperConfiguration.allowNoDefinedIdentifier) {
      errors[ValidationError.Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used] = true
      defined = true
    }
    if (count < 1 && !stepperConfiguration.allowNoDefinedIdentifier) {
      errors[ValidationError.One_Substitution_Defining_Device_Identifier_Must_Be_Used] = true
      defined = true
    }
    console.log(stepperConfiguration, mapping.mappingType)
    //console.log("Tested substitutions:", count, errors, mapping.substitutions, mapping.substitutions.filter(m => m.definesIdentifier));
    return defined ? errors : null;
  }

}

export function countDeviceIdentifiers(mapping: Mapping): number {
  return mapping.substitutions.filter(sub => definesDeviceIdentifier(mapping.targetAPI, sub)).length
}

export function checkPropertiesAreValid(mappings: Mapping[]): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const errors = {}
    let error: boolean = false;
    let defined = false

    let templateTopic = control.get('templateTopic').value;
    let templateTopicSample = control.get('templateTopicSample').value;
    let subscriptionTopic = control.get('subscriptionTopic').value;
    let id = control.get('id').value;
    let containsWildcardTemplateTopic = isWildcardTopic(subscriptionTopic);

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

    let f = (st, tt) => new RegExp(st.split`+`.join`[^/]+`.split`#`.join`.*`).test(tt)
    error = !f(subscriptionTopic, templateTopic);
    if (error) {
      errors[ValidationError.TemplateTopic_Must_Match_The_SubscriptionTopic] = true
      defined = true
    }

    // count number of "#" in subscriptionTopic
    let count_multi = (subscriptionTopic.match(/\#/g) || []).length;
    if (count_multi > 1) {
      errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
      defined = true
    }

    // count number of "+" in subscriptionTopic
    let count_single = (subscriptionTopic.match(/\+/g) || []).length;
    if (count_single > 1) {
      errors[ValidationError.Only_One_Single_Level_Wildcard] = true;
      defined = true
    }

    // wildcard "#" can only appear at the end in subscriptionTopic
    if (count_multi >= 1 && subscriptionTopic.indexOf(TOPIC_WILDCARD_MULTI) + 1 != subscriptionTopic.length) {
      errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;
      defined = true
    }

    // count number of "#" in templateTopic
    count_multi = (templateTopic.match(/\#/g) || []).length;
    if (count_multi >= 1) {
      errors[ValidationError.No_Multi_Level_Wildcard_Allowed_In_TemplateTopic] = true;
      defined = true
    }

    let splitTT: String[] = splitTopicExcludingSeparator(templateTopic);
    let splitTTS: String[] = splitTopicExcludingSeparator(templateTopicSample);
    if (splitTT.length != splitTTS.length) {
      errors[ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name];
    } else {
      for (let i = 0; i < splitTT.length; i++) {
        if ("/" == splitTT[i] && !("/" == splitTTS[i])) {
          errors[ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name];
          break;
        }
        if (("/" == splitTTS[i]) && !("/" == splitTT[i])) {
          errors[ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name];
          break;
        }
        if (!("/" == splitTT[i]) && !("+" == splitTT[i])) {
          if (splitTT[i] != splitTTS[i]) {
            errors[ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name];
            break;
          }
        }
      }
    }

    // // topic cannot be startstring of another topic
    //   error = !mappings.every(m => {
    //     return ((!topic.startsWith(m.topic) && !m.topic.startsWith(topic) || id == m.id))
    //   })
    //   if (error) {
    //     errors['Topic_Not_Substring_Of_OtherTopic'] = true
    //     defined = true
    //   }

    // error = !mappings.every(m => {
    //   return (templateTopic != m.templateTopic || id == m.id)
    // })
    // if (error) {
    //   errors[ValidationError.TemplateTopic_Not_Unique] = true
    //   defined = true
    // }

    // error = !mappings.every(m => {
    //   return ((!templateTopic.startsWith(m.templateTopic) && !m.templateTopic.startsWith(templateTopic) || id == m.id))
    // })
    // if (error && templateTopic != '') {
    //   errors[ValidationError.TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic] = true
    //   defined = true
    // }

    // if the template topic contains a wildcard a device identifier must be selected 
    // let mdi = control.get('markedDeviceIdentifier').value;
    // if (containsWildcardTemplateTopic && (mdi == undefined || mdi == '')) {
    //   errors[ValidationError.Device_Identifier_Must_Be_Selected] = true;
    //   defined = true
    // }


    // let containsWildcardTemplateTopic = isWildcardTopic(templateTopic);
    // control.get('markedDeviceIdentifier').setValidators( containsWildcardTemplateTopic? Validators.required : Validators.nullValidator);
    // control.get('markedDeviceIdentifier').updateValueAndValidity();
    //console.log("Tested topics :", errors);
    return defined ? errors : null;
  }

}

export function whatIsIt(object) {
  var stringConstructor = "test".constructor;
  var arrayConstructor = [].constructor;
  var objectConstructor = ({}).constructor;
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
  } else {
    return "don't know";
  }
}

export const isNumeric = (num: any) => (typeof (num) === 'number' || typeof (num) === "string" && num.trim() !== '') && !isNaN(num as number);

export function definesDeviceIdentifier(api: string, sub: MappingSubstitution): boolean {
  return sub.pathTarget == API[api].identifier
}