export interface MQTTAuthentication {
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active: boolean;
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
  subscriptionTopic: string;
  templateTopic: string;
  indexDeviceIdentifierInTemplateTopic: number;
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
  updateExistingDevice: boolean;
  externalIdType: string;
  snoopTemplates: SnoopStatus;
  snoopedTemplates?: string[];
  direct: boolean;
  filterType: string;
}


export interface MappingStatus {
  id: number;
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

export const DOWNLINK_API_OPTION = [
  {name: "ALARM", value:"ALARM"}
]

export const UPLINK_API_OPTION = [
  {name: "ALARM", value:"ALARM"},
  {name: "EVENT", value:"EVENT"},
  {name: "MEASUREMENT", value:"MEASUREMENT"},
  {name: "INVENTORY", value:"INVENTORY"}
]

export const LINK_DIRECT =[
  {name: 'UpLink', value: false},
  {name: 'DownLink', value: true}
]

export enum ValidationError {
  Only_One_Multi_Level_Wildcard,
  Only_One_Single_Level_Wildcard,
  Multi_Level_Wildcard_Only_At_End,
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used,
  TemplateTopic_Must_Match_The_SubscriptionTopic,
  TemplateTopic_Not_Unique,
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic,
  Device_Identifier_Must_Be_Selected
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
