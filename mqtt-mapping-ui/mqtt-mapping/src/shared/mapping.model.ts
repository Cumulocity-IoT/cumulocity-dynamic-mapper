import { IIdentified } from "@c8y/client";

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
export interface ConnectionConfiguration {
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  enabled: boolean;
  useSelfSignedCertificate: boolean;
  fingerprintSelfSignedCertificate: string;
  nameCertificate: string;
}

export interface ServiceConfiguration {
  logPayload: boolean;
  logSubstitution: boolean;
  externalExtensionEnabled?: boolean;
}

export interface MappingSubstitution {
  pathSource: string;
  pathTarget: string;
  repairStrategy: RepairStrategy;
  expandArray: boolean;
  resolve2ExternalId: boolean;
}

export interface Mapping {
  name: string;
  id: string;
  ident: string;
  subscriptionTopic?: string;
  publishTopic?: string;
  templateTopic: string;
  templateTopicSample: string;
  targetAPI: string;
  source: string;
  target: string;
  active: boolean;
  tested: boolean;
  qos: QOS;
  substitutions?: MappingSubstitution[];
  mapDeviceIdentifier: boolean;
  createNonExistingDevice: boolean;
  updateExistingDevice: boolean;
  externalIdType: string;
  snoopStatus: SnoopStatus;
  snoopedTemplates?: string[];
  mappingType: MappingType;
  extension?: ExtensionEntry;
  direction?: Direction;
  filterOutbound?: string;
  autoAckOperation?: boolean;
  lastUpdate: number;
}

export interface MappingStatus {
  id: number;
  ident: string;
  subscriptionTopic: string;
  errors: number;
  messagesReceived: number;
  snoopedTemplatesTotal: number;
  snoopedTemplatesActive: number;
}

export interface ServiceStatus {
  status: Status;
}

export interface Feature {
  outputMappingEnabled: boolean;
  externalExtensionsEnabled: boolean;
  userHasMQTTMappingCreateRole: boolean;
  userHasMQTTMappingAdminRole: boolean;
}

export interface PayloadWrapper {
  message: string;
}

export interface ExtensionEntry {
  event: string;
  name: string;
  loaded?: boolean;
  message: string;
}

export interface Extension {
  id?: string;
  name: string;
  extensionEntries: Map<String, ExtensionEntry>;
  loaded: boolean;
  external: boolean;
}

export enum ExtensionStatus {
  COMPLETE = "COMPLETE",
  PARTIALLY = "PARTIALLY",
  NOT_LOADED = "NOT_LOADED",
  UNKNOWN = "UNKNOWN",
}

export enum Status {
  CONNECTED = "CONNECTED",
  ENABLED = "ACTIVATED",
  CONFIGURED = "CONFIGURED",
  NOT_READY = "NOT_READY",
}

export enum Direction {
  INBOUND = "INBOUND",
  OUTBOUND = "OUTBOUND",
}

export const API = {
  ALARM: {
    name: "ALARM",
    identifier: "source.id",
    notificationFilter: "alarms",
  },
  EVENT: {
    name: "EVENT",
    identifier: "source.id",
    notificationFilter: "events",
  },
  MEASUREMENT: {
    name: "MEASUREMENT",
    identifier: "source.id",
    notificationFilter: "measurements",
  },
  INVENTORY: {
    name: "INVENTORY",
    identifier: "_DEVICE_IDENT_",
    notificationFilter: "managedObjects",
  },
  OPERATION: {
    name: "OPERATION",
    identifier: "deviceId",
    notificationFilter: "operations",
  },
  ALL: { name: "ALL", identifier: "*", notificationFilter: "*" },
};

export enum ValidationError {
  Only_One_Multi_Level_Wildcard,
  Only_One_Single_Level_Wildcard,
  Multi_Level_Wildcard_Only_At_End,
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used,
  One_Substitution_Defining_Device_Identifier_Must_Be_Used,
  TemplateTopic_Must_Match_The_SubscriptionTopic,
  TemplateTopic_Not_Unique,
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic,
  Target_Template_Must_Be_Valid_JSON,
  Source_Template_Must_Be_Valid_JSON,
  No_Multi_Level_Wildcard_Allowed_In_TemplateTopic,
  Device_Identifier_Must_Be_Selected,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  FilterOutbound_Must_Be_Unique
}

export const ValidationFormlyError = {
  Only_One_Multi_Level_Wildcard: {
    message: 'Only one MultiLevel wildcard "#" is allowed.',
  },
  Only_One_Single_Level_Wildcard: {
    message: 'Only one SingleLevel wildcard "+" is allowed.',
  },
  Multi_Level_Wildcard_Only_At_End: {
    message: 'MultiLevel wildcard "#" can only appear at the end.',
  },
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used: {
    message: "Only one substitution defining the DeviceIdentifier can be used.",
  },
  One_Substitution_Defining_Device_Identifier_Must_Be_Used: {
    message: 'Only one MultiLevel wildcard "#" is allowed.',
  },
  TemplateTopic_Must_Match_The_SubscriptionTopic: {
    message: "The TemplateTopic must match the SubscriptionTopic.",
  },
  TemplateTopic_Not_Unique: {
    message: "This TemplateTopic must be unique across other TemplateTopics.",
  },
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic: {
    message:
      "This TemplateTopic can't be the starting part of another  TemplateTopic or vice.",
  },
  Target_Template_Must_Be_Valid_JSON: {
    message: "TargetTemplate must be valid JSON.",
  },
  Source_Template_Must_Be_Valid_JSON: {
    message: "SourceTemplate must be valid JSON..",
  },
  No_Multi_Level_Wildcard_Allowed_In_TemplateTopic: {
    message: "No MultiLevel wildcard is allowed in TemplateTopic.",
  },
  Device_Identifier_Must_Be_Selected: {
    message: "DeviceIdentifier must be selected.",
  },
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
    {
      message:
        "The TemplateTopic and TemplateTopicSample do not have same number of levels in the Topic Name",
    },
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
    {
      message:
        "TemplateTopic and TemplateTopicSample do not have same structure in the Topic Name.",
    },
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
    {
      message:
        "The PublishTopic and TemplateTopicSample do not have same number of levels in the Topic Name",
    },
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
    {
      message:
        "PublishTopic and TemplateTopicSample do not have same structure in the Topic Name.",
    },
  FilterOutbound_Must_Be_Unique:
    {
      message:
        "FilterOutbound must be unique within all outbound mappings.",
    },
};

export enum QOS {
  AT_MOST_ONCE = "AT_MOST_ONCE",
  AT_LEAST_ONCE = "AT_LEAST_ONCE",
  EXACTLY_ONCE = "EXACTLY_ONCE",
}

export enum SnoopStatus {
  NONE = "NONE",
  ENABLED = "ENABLED",
  STARTED = "STARTED",
  STOPPED = "STOPPED",
}

export enum Operation {
  ACTIVATE_MAPPING,
  CONNECT,
  DISCONNECT,
  REFRESH_STATUS_MAPPING,
  RELOAD_EXTENSIONS,
  RELOAD_MAPPINGS,
  RESET_STATUS_MAPPING,
  REFRESH_NOTFICATIONS_SUBSCRIPTIONS,
}

export enum MappingType {
  JSON = "JSON",
  FLAT_FILE = "FLAT_FILE",
  GENERIC_BINARY = "GENERIC_BINARY",
  PROTOBUF_STATIC = "PROTOBUF_STATIC",
  PROCESSOR_EXTENSION = "PROCESSOR_EXTENSION",
}

export enum RepairStrategy {
  DEFAULT = "DEFAULT",
  USE_FIRST_VALUE_OF_ARRAY = "USE_FIRST_VALUE_OF_ARRAY",
  USE_LAST_VALUE_OF_ARRAY = "USE_LAST_VALUE_OF_ARRAY",
  IGNORE = "IGNORE",
  REMOVE_IF_MISSING = "REMOVE_IF_MISSING",
  REMOVE_IF_NULL = "REMOVE_IF_NULL",
  CREATE_IF_MISSING = "CREATE_IF_MISSING",
}

export class C8YAPISubscription {
  api: string;
  devices: IIdentified[];
}
