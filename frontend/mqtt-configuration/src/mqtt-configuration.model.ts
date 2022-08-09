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