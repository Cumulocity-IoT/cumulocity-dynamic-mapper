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
import { API, Direction, Mapping, MappingType } from './mapping.model';

export const SAMPLE_TEMPLATES_C8Y = {
  MEASUREMENT: `{                                               
    "c8y_TemperatureMeasurement": {
        "T": {
            "value": 110,
              "unit": "C" }
          },
      "time":"2022-08-05T00:14:49.389+02:00",
      "type": "c8y_TemperatureMeasurement"
  }`,
  ALARM: `{                                            
    "severity": "MAJOR",
    "status": "ACTIVE",
    "text": "This is a new test alarm!",
    "time": "2022-08-05T00:14:49.389+02:00",
    "type": "c8y_TestAlarm"
  }`,
  EVENT: `{ 
    "text": "This is a new test event.",
    "time": "2022-08-05T00:14:49.389+02:00",
    "type": "c8y_TestEvent"
 }`,
  INVENTORY: `{ 
    "c8y_IsDevice": {},
    "name": "Vibration Sensor",
    "com_cumulocity_model_Agent": {},
    "type": "maker_Vibration_Sensor"
 }`,
  OPERATION: `{ 
   "description": "New camera operation!",
   "type": "maker_Vibration_Sensor"
}`
};

export const SAMPLE_TEMPLATES_EXTERNAL = {
  MEASUREMENT: `{                                               
    "Temperature": {
        "value": 110,
        "unit": "C" },
      "time":"2022-08-05T00:14:49.389+02:00",
      "deviceId":"909090"
  }`,
  ALARM: `{                                            
    "deviceId":"909090",
    "alarmType": "TestAlarm",
    "description": "This is a new test alarm!",
    "severity": "MAJOR",
    "status": "ACTIVE",
    "time": "2022-08-05T00:14:49.389+02:00"
  }`,
  EVENT: `{ 
    "deviceId":"909090",
    "description": "This is a new test event.",
    "time": "2022-08-05T00:14:49.389+02:00",
    "eventType": "TestEvent"
 }`,
  INVENTORY: `{ 
    "name": "Vibration Sensor",
    "type": "maker_Vibration_Sensor",
    "id": "909090"
 }`,
  OPERATION: `{ 
   "deviceId": "909090",
   "description": "New camera operation!",
   "type": "maker_Vibration_Sensor"
  }`,
  FLAT_FILE:
    '{"message":"165, 14.5, \\"2022-08-06T00:14:50.000+02:00\\",\\"c8y_FuelMeasurement\\""}',
  HEX:
    '{"message":"3635 2c20 342e 352c 2022 3230 3232 2d30 382d 3036 5430 303a 3135 3a35 302e 3030 302b 3032 3a30 3022 2c22 6338 795f 4675 656c 4d65 6173 7572 656d 656e 7422 "}'
};

export const SCHEMA_EVENT = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'EVENT',
  required: ['type', 'text', 'time'],
  properties: {
    source: {
      $id: '#/properties/source',
      type: 'object',
      title: 'The managed object to which the event is associated.',
      allOf: [{ required: ['id'] }],
      properties: {
        id: {
          type: 'string',
          minLength: 1,
          title: 'SourceID'
        }
      }
    },
    type: {
      $id: '#/properties/type',
      type: 'string',
      title: 'Type of the event.'
    },
    text: {
      $id: '#/properties/text',
      type: 'string',
      title: 'Text of the event.'
    },
    time: {
      $id: '#/properties/time',
      type: 'string',
      title: 'Type of the event.',
      pattern:
        '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$'
    }
  }
};

export const SCHEMA_ALARM = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'ALARM',
  required: ['type', 'text', 'time', 'severity'],
  properties: {
    source: {
      $id: '#/properties/source',
      type: 'object',
      title: 'The managed object to which the alarm is associated.',
      allOf: [{ required: ['id'] }],
      properties: {
        id: {
          type: 'string',
          minLength: 1,
          title: 'SourceID'
        }
      }
    },
    type: {
      $id: '#/properties/type',
      type: 'string',
      title: 'Type of the alarm.'
    },

    severity: {
      $id: '#/properties/severity',
      type: 'string',
      title: 'Severity of the alarm.',
      pattern: '^((CRITICAL)|(MAJOR)|(MINOR)|(WARNING))$'
    },
    text: {
      $id: '#/properties/text',
      type: 'string',
      title: 'Text of the alarm.'
    },
    time: {
      $id: '#/properties/time',
      type: 'string',
      title: 'Type of the alarm.',
      pattern:
        '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$'
    }
  }
};

export const SCHEMA_MEASUREMENT = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'MEASUREMENT',
  required: ['type', 'time'],
  properties: {
    source: {
      $id: '#/properties/source',
      type: 'object',
      title: 'The managed object to which the measurement is associated.',
      allOf: [{ required: ['id'] }],
      properties: {
        id: {
          type: 'string',
          minLength: 1,
          title: 'SourceID'
        }
      }
    },
    type: {
      $id: '#/properties/type',
      type: 'string',
      title: 'Type of the measurement.'
    },
    time: {
      $id: '#/properties/time',
      type: 'string',
      title: 'Type of the measurement.',
      pattern:
        '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[+-]\\d{2}:\\d{2})?)$'
    }
  }
};

export const SCHEMA_INVENTORY = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'INVENTORY',
  required: ['c8y_IsDevice', 'type', 'name'],
  properties: {
    c8y_IsDevice: {
      $id: '#/properties/c8y_IsDevice',
      type: 'object',
      title: 'Mark as device.',
      properties: {}
    },
    type: {
      $id: '#/properties/type',
      type: 'string',
      title: 'Type of the device.'
    },
    name: {
      $id: '#/properties/name',
      type: 'string',
      title: 'Name of the device.'
    },
    id: {
      $id: '#/properties/id',
      type: 'string',
      title: 'Cumulocity id of the device.'
    }
  }
};

export const SCHEMA_C8Y_INVENTORY = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'INVENTORY',
  required: ['c8y_IsDevice', 'type', 'name'],
  properties: {
    c8y_IsDevice: {
      $id: '#/properties/c8y_IsDevice',
      type: 'object',
      title: 'Mark as device.',
      properties: {}
    },
    type: {
      $id: '#/properties/type',
      type: 'string',
      title: 'Type of the device.'
    },
    name: {
      $id: '#/properties/name',
      type: 'string',
      title: 'Name of the device.'
    },
    id: {
      $id: '#/properties/id',
      type: 'string',
      title: 'Cumulocity id of the device.',
    }
  }
};

export const SCHEMA_OPERATION = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'OPERATION',
  required: [],
  properties: {
    deviceId: {
      $id: '#/properties/deviceId',
      type: 'string',
      title:
        'Identifier of the target device where the operation should be performed..'
    },
    description: {
      $id: '#/properties/description',
      type: 'string',
      title: 'Description of the operation.'
    }
  }
};

export const SCHEMA_PAYLOAD = {
  definitions: {},
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'http://example.com/root.json',
  type: 'object',
  title: 'PAYLOAD',
  required: []
};

export const MAPPING_TYPE = 'd11r_mapping';
export const PROCESSOR_EXTENSION_TYPE = 'd11r_processorExtension';
export const MAPPING_TEST_DEVICE_TYPE = 'd11r_testDevice';
export const MAPPING_TEST_DEVICE_FRAGMENT = 'd11r_testDevice';
export const MAPPING_FRAGMENT = 'd11r_mapping';
export const CONNECTOR_FRAGMENT = 'd11r_connector';
export const MAPPING_GENERATED_TEST_DEVICE = 'd11r_device_generatedType';

export const BASE_URL = 'service/dynamic-mapping-service';
export const PATH_OPERATION_ENDPOINT = 'operation';
export const PATH_CONFIGURATION_CONNECTION_ENDPOINT = 'configuration/connector';
export const PATH_CONFIGURATION_SERVICE_ENDPOINT = 'configuration/service';
export const PATH_CONFIGURATION_CODE_TEMPLATE_ENDPOINT = 'configuration/code';
export const PATH_MAPPING_TREE_ENDPOINT = 'monitoring/tree';
export const PATH_MAPPING_ACTIVE_SUBSCRIPTIONS_ENDPOINT = 'monitoring/tree';
export const PATH_STATUS_CONNECTORS_ENDPOINT = 'monitoring/status/connectors';
export const PATH_FEATURE_ENDPOINT = 'configuration/feature';
export const PATH_EXTENSION_ENDPOINT = 'extension';
export const PATH_SUBSCRIPTION_ENDPOINT = 'subscription';
export const PATH_DEPLOYMENT_EFFECTIVE_ENDPOINT = 'deployment/effective';
export const PATH_DEPLOYMENT_DEFINED_ENDPOINT = 'deployment/defined';
export const PATH_SUBSCRIPTIONS_ENDPOINT = 'subscription';
export const PATH_MAPPING_ENDPOINT = 'mapping';

export const AGENT_ID = 'd11r_mappingService';
export const COLOR_HIGHLIGHTED: string = 'lightgrey';

export function getExternalTemplate(mapping: Mapping): any {
  if (
    mapping.mappingType == MappingType.FLAT_FILE ||
    mapping.mappingType == MappingType.HEX
  ) {
    return SAMPLE_TEMPLATES_EXTERNAL[mapping.mappingType];
  } else {
    return SAMPLE_TEMPLATES_EXTERNAL[mapping.targetAPI];
  }
}
export function getSchema(
  targetAPI: string,
  direction: Direction,
  isTarget: boolean,
  isTesting: boolean
): any {
  if (
    (isTarget && direction == Direction.INBOUND) ||
    (!isTarget && direction == Direction.OUTBOUND)
  ) {
    if (targetAPI == API.ALARM.name) {
      return SCHEMA_ALARM;
    } else if (targetAPI == API.EVENT.name) {
      return SCHEMA_EVENT;
    } else if (targetAPI == API.MEASUREMENT.name) {
      return SCHEMA_MEASUREMENT;
    } else if (targetAPI == API.INVENTORY.name) {
      if (isTarget) return SCHEMA_C8Y_INVENTORY;
      else return SCHEMA_INVENTORY
    } else {
      return SCHEMA_OPERATION;
    }
  } else {
    return SCHEMA_PAYLOAD;
  }
}

export function createCustomUuid(): string {
  const id = Math.random().toString(36).slice(-6);
  return id;
}

export function nextIdAndPad(id: number, padding: number): string {
  return (id + 1).toString(10).padStart(padding, '0');
}

export const NODE1 = 'node1';
export const NODE2 = 'node2';
export const NODE3 = 'node3';
