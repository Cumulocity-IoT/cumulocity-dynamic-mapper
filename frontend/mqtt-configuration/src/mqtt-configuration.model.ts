export interface MQTTAuthentication {
  mqttHost: string;
  mqttPort: string;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active:boolean
}

export interface MQTTMappingSubstitution {
  name: string,
  jsonPath: string,
}

export interface MQTTMapping {
  id: number,
  topic: string,
  targetAPI: string,
  source: string,
  target: string,
  lastUpdate: number,
  active: boolean,
  tested: boolean,
  createNoExistingDevice: boolean,
  qos: number,
  substitutions?: MQTTMappingSubstitution[]
}

export const SAMPLE_TEMPLATES = {
  measurement: `{                                               
    \"c8y_TemperatureMeasurement\": {
        \"T\": {
            \"value\": \${value},
              \"unit\": \"C\" }
          },
      \"time\":\"\${time}\",
      \"source\": {
        \"id\":\"\${device}\" },
      \"type\": \"\${type}\"
  }`,
  alarm: `{                                            
    \"source\": {
    \"id\": \"\${device}\"
    },\
    \"type\": \"\${type}\",
    \"text\": \"\${text}\",
    \"severity\": \"\${severity}\",
    \"status\": \"\${status}\",
    \"time\": \"\${time}\"
  }`,
  event: `{ 
    \"source\": {
    \"id\": \"\${device}\"
    },
    \"text\": \"\${text}\",
    \"time\": \"\${time}\",
    \"type\": \"\${type}\"
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