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
import { ResolveFn } from '@angular/router';
import { API, Direction, Feature, Mapping, MappingType } from './mapping.model';
import { SharedService } from '../service/shared.service';
import { inject } from '@angular/core';

const SAMPLE_TIME_PLACEHOLDER = '__SAMPLE_NOW__';
const HEX_PAYLOAD_PLACEHOLDER = '__HEX_PAYLOAD_NOW__';

function generateHexPayload(): string {
  const now = new Date().toISOString();
  const csv = `65, 4.5, "${now}","c8y_FuelMeasurement"`;
  const hexPairs = Array.from(csv).map(c => c.charCodeAt(0).toString(16).padStart(2, '0'));
  const groups: string[] = [];
  for (let i = 0; i < hexPairs.length; i += 2) {
    groups.push(hexPairs[i] + (hexPairs[i + 1] ?? ''));
  }
  return `{"payload":"${groups.join(' ')} "}`;
}

function withCurrentTime(template: string): string {
  if (template === HEX_PAYLOAD_PLACEHOLDER) {
    return generateHexPayload();
  }
  return template.replace(SAMPLE_TIME_PLACEHOLDER, new Date().toISOString());
}

function dynamicTemplates<T extends Record<string, string>>(base: T): T {
  return new Proxy(base, {
    get(target, prop: string) {
      const value = target[prop];
      return typeof value === 'string' ? withCurrentTime(value) : value;
    }
  });
}

export const SAMPLE_TEMPLATES_C8Y = dynamicTemplates({
  MEASUREMENT: `{
    "c8y_TemperatureMeasurement": {
        "T": {
            "value": 110,
              "unit": "C" }
          },
      "time":"${SAMPLE_TIME_PLACEHOLDER}",
      "type": "c8y_TemperatureMeasurement"
  }`,
  ALARM: `{
    "severity": "MAJOR",
    "status": "ACTIVE",
    "text": "This is a new test alarm!",
    "time": "${SAMPLE_TIME_PLACEHOLDER}",
    "type": "c8y_TestAlarm"
  }`,
  EVENT: `{
    "text": "This is a new test event.",
    "time": "${SAMPLE_TIME_PLACEHOLDER}",
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
});

export const SAMPLE_TEMPLATES_EXTERNAL = dynamicTemplates({
  MEASUREMENT: `{
    "Temperature": {
        "value": 110,
        "unit": "C" },
      "time":"${SAMPLE_TIME_PLACEHOLDER}",
      "deviceId":"909090"
  }`,
  ALARM: `{
    "deviceId":"909090",
    "alarmType": "TestAlarm",
    "description": "This is a new test alarm!",
    "severity": "MAJOR",
    "status": "ACTIVE",
    "time": "${SAMPLE_TIME_PLACEHOLDER}"
  }`,
  EVENT: `{
    "deviceId":"909090",
    "description": "This is a new test event.",
    "time": "${SAMPLE_TIME_PLACEHOLDER}",
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
  FLAT_FILE: `{"payload":"165, 14.5, \\"${SAMPLE_TIME_PLACEHOLDER}\\",\\"c8y_FuelMeasurement\\""}`,
  HEX: HEX_PAYLOAD_PLACEHOLDER
});

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

export const BASE_URL = 'service/dynamic-mapper-service';
export const BASE_AI_URL = 'service/ai';
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
export const PATH_RELATION_ENDPOINT = 'relation';
export const PATH_TESTING_ENDPOINT = 'test';
export const PATH_MAPPING_ENDPOINT = 'mapping';
export const PATH_AGENT_ENDPOINT = 'agent';

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
  getTesting: boolean
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

export const UUID_LENGTH = 8;

export function createCustomUuid(): string {
  const id = Math.random().toString(36).slice(-UUID_LENGTH);
  return id;
}

export function nextIdAndPad(id: number, padding: number): string {
  return (id + 1).toString(10).padStart(padding, '0');
}

export const NODE1 = 'node1';
export const NODE2 = 'node2';
export const NODE3 = 'node3';

export const featureResolver: ResolveFn<Feature> = async (route, state) => {
  const sharedService = inject(SharedService);
  return await sharedService.getFeatures();
};
