export interface ConnectionConfiguration {
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active: boolean;
}

export interface ServiceConfiguration {
  logPayload: boolean;
}

export class MappingSubstitution {
  public pathSource: string;
  public pathTarget: string;
  public definesIdentifier?: boolean
  constructor (
    ps: string,
    pt: string,
    di: boolean
  ){
    this.pathSource = ps;
    this.pathTarget = pt;
    this.definesIdentifier = di;
  }
  reset() {
    this.pathSource = '';
    this.pathTarget = '';
    this.definesIdentifier = false;
  }
  isValid() {
    return this.hasPathSource() && this.hasPathTarget()
  }
  hasPathSource() {
    return this.pathSource != ''
  }
  hasPathTarget() {
    return this.pathTarget != ''
  }
}

export interface Mapping {
  id: number;
  ident: string;
  subscriptionTopic: string;
  templateTopic: string;
  templateTopicSample: string;
  targetAPI: string;
  source: string;
  target: string;
  lastUpdate: number;
  active: boolean;
  tested: boolean;
  qos: QOS;
  substitutions?: MappingSubstitution[];
  mapDeviceIdentifier: boolean;
  createNonExistingDevice: boolean;
  repairStrategy: RepairStrategy;
  updateExistingDevice: boolean;
  externalIdType: string;
  snoopStatus: SnoopStatus;
  snoopedTemplates?: string[];
}


export interface MappingStatus {
  id: number;
  ident: string;
  subscriptionTopic: string;
  errors: number;
  messagesReceived: number;
  snoopedTemplatesTotal: number;
  snoopedTemplatesActive: number
}

export interface ServiceStatus {
  status: Status;
}

export enum Status {
  CONNECTED = "CONNECTED",
  ACTIVATED = "ACTIVATED",
  CONFIGURED = "CONFIGURED",
  NOT_READY = "NOT_READY"
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
  TemplateTopic_Must_Match_The_SubscriptionTopic,
  TemplateTopic_Not_Unique,
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic,
  Target_Template_Must_Be_Valid_JSON, 
  Source_Template_Must_Be_Valid_JSON, 
  No_Multi_Level_Wildcard_Allowed_In_TemplateTopic,
  Device_Identifier_Must_Be_Selected,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
}

export enum QOS {
  AT_MOST_ONCE = "AT_MOST_ONCE",
  AT_LEAST_ONCE = "AT_LEAST_ONCE", 
  EXACTLY_ONCE = "EXACTLY_ONCE",
}


export enum SnoopStatus {
  NONE = "NONE",
  ENABLED = "ENABLED",
  STARTED = "STARTED",
  STOPPED = "STOPPED"
}

export enum Operation {
  RELOAD,
  CONNECT,
  DISCONNECT, 
  RESFRESH_STATUS_MAPPING
}

export enum RepairStrategy {
  USE_FIRST_VALUE_OF_ARRAY = "USE_FIRST_VALUE_OF_ARRAY",
  USE_LAST_VALUE_OF_ARRAY = "USE_LAST_VALUE_OF_ARRAY",
  IGNORE = "IGNORE",
}
