import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from "@angular/forms"
import { API, Mapping, ValidationError } from "./mqtt-configuration.model"

export const SAMPLE_TEMPLATES = {
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
 }`
  ,
  INVENTORY: `{ 
    \"c8y_IsDevice\": {},
    \"name\": \"Vibration Sensor\",
    \"type\": \"maker_Vibration_Sensor\"
 }`
}

export const SCHEMA_EVENT = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'Event',
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
  'title': 'Alarm',
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
  'title': 'Measurement',
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

export const SCHEMA_PAYLOAD = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'Payload',
  'required': [
  ],
}

export const TOKEN_DEVICE_TOPIC = "DEVICE_IDENT";

export function getSchema(targetAPI: string): any {
  if (targetAPI == API.ALARM) {
    return SCHEMA_ALARM;
  } else if (targetAPI == API.EVENT) {
    return SCHEMA_EVENT;
  } else if (targetAPI == API.MEASUREMENT) {
    return SCHEMA_MEASUREMENT;
  } else {
    return SCHEMA_INVENTORY;
  }
}

export function normalizeTopic(topic: string) {
  if (topic == undefined) topic = '';
  let nt = topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
  nt = "/" + nt;
  // console.log("Topic normalized:", topic, nt);
  // append trailing slash if last character is not wildcard #
   nt = nt.concat(nt.endsWith(TOPIC_WILDCARD_MULTI)|| nt.endsWith(TOPIC_WILDCARD_SINGLE) ? '' : '/')
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
  let count_multi = (topic.match(/\\#/g) || []).length;
  if (count_multi > 1) errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
  // count number of "+"
  let count_single = (topic.match(/\\+/g) || []).length;
  if (count_single > 1) errors[ValidationError.Only_One_Single_Level_Wildcard] = true;

  if (count_multi >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) != topic.length) errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;

  return errors;
}

export function isTopicUnique(mapping: Mapping, mappings: Mapping[]): boolean {
  let result = true;
  result = mappings.every(m => {
    return ((!mapping.topic.startsWith(m.topic) && !m.topic.startsWith(mapping.topic)) || mapping.id == m.id);
  })
  return result;
}

export function isTemplateTopicUnique(mapping: Mapping, mappings: Mapping[]): boolean {
  let result = true;
  result = mappings.every(m => {
    return ((!mapping.templateTopic.startsWith(m.templateTopic) && !m.templateTopic.startsWith(mapping.templateTopic)) || mapping.id == m.id);
  })
  return result;
}

export const TOPIC_WILDCARD_MULTI = "#"
export const TOPIC_WILDCARD_SINGLE = "+"

export function isWildcardTopic(topic: string): boolean {
  const result = topic.includes(TOPIC_WILDCARD_MULTI) || topic.includes(TOPIC_WILDCARD_SINGLE);
  return result;
}

export enum SnoopStatus {
  NONE = "NONE",
  ENABLED = "ENABLED",
  STARTED = "STARTED",
  STOPPED = "STOPPED"
}


export function isSubstituionValid(mapping: Mapping): boolean {
  let count = mapping.substitutions.filter(m => m.definesIdentifier).map( m => 1).reduce((previousValue: number, currentValue: number, currentIndex: number, array: number[]) => {
    return previousValue + currentValue;
  }, 0)
  return (count > 1);
}

export function checkSubstituionIsValid(mapping: Mapping): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const errors = {}
    let defined = false

    let count = mapping.substitutions.filter(m => m.definesIdentifier).map( m => 1).reduce((previousValue: number, currentValue: number, currentIndex: number, array: number[]) => {
      return previousValue + currentValue;
    }, 0)
    if (count > 1) {
      errors[ValidationError.Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used] = true
      defined = true
    }
    console.log("Tested substitutions :", errors);
    return defined ? errors : null;
  }

}

export function checkPropertiesAreValid(mappings: Mapping[]): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const errors = {}
    let error: boolean = false;
    let defined = false

    let templateTopic = normalizeTopic(control.get('templateTopic').value);
    let topic = normalizeTopic(control.get('topic').value);
    let id = control.get('id').value;

    // in the topic a multi level wildcard "*" can appear and is replaced by a single level wildcard "+"
    // for comparison the "#" must then be replaced by a "+"
    let nt = topic.trim().replace(/\#+$/, '+')
    error = !templateTopic.startsWith(nt);
    if (error) {
      errors[ValidationError.Topic_Must_Be_Substring_Of_TemplateTopic] = true
      defined = true
    }

    // count number of "#"
    let count_multi = (topic.match(/\\#/g) || []).length;
    if (count_multi > 1) {
      errors[ValidationError.Only_One_Multi_Level_Wildcard] = true;
      defined = true
    }

    // count number of "+"_
    let count_single = (topic.match(/\\+/g) || []).length;
    if (count_single > 1) {
      errors[ValidationError.Only_One_Single_Level_Wildcard] = true;
      defined = true
    }

    // wildcard "'" can only appear at the end
    if (count_multi >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) != topic.length) {
      errors[ValidationError.Multi_Level_Wildcard_Only_At_End] = true;
      defined = true
    }

    /*       // topic cannot be startstring of another topic
          error = !mappings.every(m => {
            return ((!topic.startsWith(m.topic) && !m.topic.startsWith(topic) || id == m.id))
          })
          if (error) {
            errors['Topic_Not_Substring_Of_OtherTopic'] = true
            defined = true
          } */

    error = !mappings.every(m => {
      return (templateTopic != m.templateTopic || id == m.id)
    })
    if (error) {
      errors[ValidationError.TemplateTopic_Not_Unique] = true
      defined = true
    }

    error = !mappings.every(m => {
      return ((!templateTopic.startsWith(m.templateTopic) && !m.templateTopic.startsWith(templateTopic) || id == m.id))
    })
    if (error && templateTopic != '') {
      errors[ValidationError.TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic] = true
      defined = true
    }


    // let containsWildcardTemplateTopic = isWildcardTopic(templateTopic);
    // control.get('markedDeviceIdentifier').setValidators( containsWildcardTemplateTopic? Validators.required : Validators.nullValidator);
    // control.get('markedDeviceIdentifier').updateValueAndValidity();
    //console.log("Tested topics :", errors);
    return defined ? errors : null;
  }

}

