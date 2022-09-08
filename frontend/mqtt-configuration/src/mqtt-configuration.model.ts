export interface MQTTAuthentication {
  mqttHost: string;
  mqttPort: string;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active: boolean;
}

export interface MQTTMappingSubstitution {
  pathSource: string,
  pathTarget: string,
}

export interface MQTTMapping {
  id: number,
  topic: string,
  templateTopic: string,
  indexDeviceIdentifierInTemplateTopic: number, 
  targetAPI: string,
  source: string,
  target: string,
  lastUpdate: number,
  active: boolean,
  tested: boolean,
  createNoExistingDevice: boolean,
  qos: number,
  substitutions?: MQTTMappingSubstitution[];
  mapDeviceIdentifier:boolean;
  externalIdType: string,
  snoopTemplates: SnoopStatus,
  snoopedTemplates:string[]
}

export const SAMPLE_TEMPLATES = {
  measurement: `{                                               
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
  alarm: `{                                            
    \"source\": {
    \"id\": \"909090\"
    },\
    \"type\": \"c8y_TestAlarm\",
    \"text\": \"This is a new test alarm!\",
    \"severity\": \"MAJOR\",
    \"status\": \"ACTIVE\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\"
  }`,
  event: `{ 
    \"source\": {
    \"id\": \"909090\"
    },
    \"text\": \"This is a new test event.\",
    \"time\": \"2022-08-05T00:14:49.389+02:00\",
    \"type\": \"c8y_TestEvent\"
 }`
}

export const APIs = ['measurement', 'event', 'alarm']

export const QOSs = [{ name: 'At most once', value: 0 },
{ name: 'At least once', value: 1 },
{ name: 'Exactly once', value: 2 }]



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
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the event.',
      },
      'text':{
        '$id': '#/properties/text',
        'type': 'string',
        'title': 'Text of the event.',
      },
      'time':{
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
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the alarm.',
      },

      'severity':{
        '$id': '#/properties/severity',
        'type': 'string',
        'title': 'Severity of the alarm.',
        'pattern': '^((CRITICAL)|(MAJOR)|(MINOR)|(WARNING))$'
      },
      'text':{
        '$id': '#/properties/text',
        'type': 'string',
        'title': 'Text of the alarm.',
      },
      'time':{
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
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the measurement.',
      },
      'time':{
        '$id': '#/properties/time',
        'type': 'string',
        'title': 'Type of the measurement.',
        'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
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

export function getSchema(targetAPI: string): any {
  if (targetAPI == "alarm") {
    return SCHEMA_ALARM;
  } else if (targetAPI == "event"){
    return SCHEMA_EVENT;
  } else {
    return SCHEMA_MEASUREMENT;
  }
}

export function normalizeTopic(topic: string) {
  if (topic == undefined) topic = '';
  let nt = topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
  console.log("Topic normalized:", topic, nt);
  // append trailing slash if last character is not wildcard #
  nt = nt.concat(nt.endsWith(TOPIC_WILDCARD) ? '' : '/')
  return nt
}

export function isTemplateTopicUnique(templateTopic: String, id: number, mappings: MQTTMapping[]): boolean {
  let result = true;
  result = mappings.every(m => {
    if (templateTopic == m.templateTopic && id != m.id) {
      return false;
    } else {
      return true;
    }
  })
  return result;
}

export function isTopicIsUnique(topic: string, id: number, mappings: MQTTMapping[]): boolean {
  let result = true;
  result = mappings.every(m => {
    if (topic == m.topic && id != m.id) {
      return false;
    } else {
      return true;
    }
  })
  return result;
}

export const TOPIC_WILDCARD = "#"

export function isWildcardTopic( topic: string): boolean {
  const result = topic.includes(TOPIC_WILDCARD);
  return result;
}

export enum SnoopStatus {
  NONE = "NONE",
  ENABLED = "ENABLED",
  STARTED = "STARTED",
  STOPPED = "STOPPED"
}