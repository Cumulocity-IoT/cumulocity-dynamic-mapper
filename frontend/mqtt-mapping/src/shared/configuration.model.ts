import { TOKEN_DEVICE_TOPIC } from "./helper";

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
  logSubstitution: boolean;
}

export class MappingSubstitution {
  public pathSource: string;
  public pathTarget: string;
  public repairStrategy: RepairStrategy;
  public expandArray: boolean;
  constructor(
    ps: string,
    pt: string,
    rs: RepairStrategy,
    di: boolean
  ) {
    this.pathSource = ps;
    this.pathTarget = pt;
    this.repairStrategy = rs;
  }
  reset() {
    this.pathSource = '';
    this.pathTarget = '';
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
  lastUpdate: number;
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

export interface PayloadWrapper {
  message: string;
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

export enum MappingType {
  JSON = "JSON",
  FLAT_FILE = "FLAT_FILE",
}

export enum RepairStrategy {
  DEFAULT = "DEFAULT",
  USE_FIRST_VALUE_OF_ARRAY = "USE_FIRST_VALUE_OF_ARRAY",
  USE_LAST_VALUE_OF_ARRAY = "USE_LAST_VALUE_OF_ARRAY",
  IGNORE = "IGNORE",
  REMOVE_IF_MISSING = "REMOVE_IF_MISSING",
}
