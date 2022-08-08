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
  measurement: `
  {                                               
    \"c8y_TemperatureMeasurement\": {
        \"T\": {
            \"value\": 25,
              \"unit\": \"C\" }
          },
      \"time\":\"2013-06-22T17:03:14.000+02:00\",
      \"source\": {
        \"id\":\"10200\" },
      \"type\": \"c8y_TemperatureMeasurement\"
  }`,
  alarm: `
  {                                            
    \"source\": {
    \"id\": \"251982\"
    },        \
    \"type\": \"c8y_UnavailabilityAlarm\",
    \"text\": \"No data received from the device within the required interval.\",
    \"severity\": \"MAJOR\",
    \"status\": \"ACTIVE\",
    \"time\": \"2020-03-19T12:03:27.845Z\"
  }`,
  event: `
  { 
    \"source\": {
    \"id\": \"251982\"
    },
    \"text\": \"Sms sent: Alarm occurred\",
    \"time\": \"2020-03-19T12:03:27.845Z\",
    \"type\": \"c8y_OutgoingSmsLog\"
 }`
}

export const APIs = ['measurement', 'event', 'alarm']

export const QOSs = [{ name: 'At most once', value: 0 },
{ name: 'At least once', value: 1 },
{ name: 'Exactly once', value: 2 }]