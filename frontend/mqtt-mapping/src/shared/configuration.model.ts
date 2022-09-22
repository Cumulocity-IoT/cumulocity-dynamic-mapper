export interface MQTTAuthentication {
  mqttHost: string;
  mqttPort: string;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active: boolean;
}

export interface MappingSubstitution {
  pathSource: string;
  pathTarget: string;
  definesIdentifier?: boolean;
}

export interface Mapping {
  id: number;
  topic: string;
  templateTopic: string;
  indexDeviceIdentifierInTemplateTopic: number;
  targetAPI: string;
  source: string;
  target: string;
  lastUpdate: number;
  active: boolean;
  tested: boolean;
  createNoExistingDevice: boolean;
  qos: number;
  substitutions?: MappingSubstitution[];
  mapDeviceIdentifier: boolean;
  externalIdType: string;
  snoopTemplates: SnoopStatus;
  snoopedTemplates?: string[];
}


export interface MappingStatus {
  id: number;
  errors: number;
  messagesReceived: number;
  snoopedTemplatesTotal: number;
  snoopedTemplatesActive: number
}

export interface StatusMessage {
  count: number;
}

export enum API {
  ALARM = "ALARM",
  EVENT = "EVENT",
  MEASUREMENT = "MEASUREMENT",
  INVENTORY = "INVENTORY"
}

export enum ValidationError {
  Only_One_Multi_Level_Wildcard,
  Only_One_Single_Level_Wildcard,
  Multi_Level_Wildcard_Only_At_End,
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used,
  Topic_Must_Be_Substring_Of_TemplateTopic,
  TemplateTopic_Not_Unique,
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic
}

export const QOS = [{ name: 'At most once', value: 0 },
{ name: 'At least once', value: 1 },
{ name: 'Exactly once', value: 2 }]


export enum SnoopStatus {
  NONE = "NONE",
  ENABLED = "ENABLED",
  STARTED = "STARTED",
  STOPPED = "STOPPED"
}
