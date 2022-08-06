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