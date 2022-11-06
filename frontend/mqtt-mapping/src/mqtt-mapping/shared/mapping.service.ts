import { Injectable } from '@angular/core';
import { AlarmService, EventService, IAlarm, IdentityService, IEvent, IExternalIdentity, IManagedObject, IMeasurement, InventoryService, IResult, MeasurementService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { API, Mapping, ProcessingContext, ProcessingType, RepairStrategy, SubstituteValue, SubstituteValueType } from '../../shared/configuration.model';
import { isNumeric, MAPPING_FRAGMENT, MAPPING_TYPE, MQTT_TEST_DEVICE_TYPE, splitTopicExcludingSeparator, TIME, TOKEN_TOPIC_LEVEL, whatIsIt } from '../../shared/helper';

@Injectable({ providedIn: 'root' })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private identity: IdentityService,
    private event: EventService,
    private alarm: AlarmService,
    private measurement: MeasurementService,
    private configurationService: BrokerConfigurationService,
    private alert: AlertService) { }

  private agentId: string;
  private testDeviceId: string;
  private mappingId: string;
  private JSONATA = require("jsonata");


  async loadTestDevice(): Promise<void> {
    if (!this.testDeviceId) {
      this.testDeviceId = await this.configurationService.initializeTestDevice();
    }
  }

  async loadMappings(): Promise<Mapping[]> {
    if (!this.agentId) {
      this.agentId = await this.configurationService.initializeMQTTAgent();
    }
    console.log("MappingService: Found MQTTAgent!", this.agentId);

    let identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: MAPPING_TYPE
    };
    try {
      const { data, res } = await this.identity.detail(identity);
      this.mappingId = data.managedObject.id as string;
      const response: IResult<IManagedObject> = await this.inventory.detail(this.mappingId);
      return response.data[MAPPING_FRAGMENT] as Mapping[];
    } catch (e) {
      console.log("So far no mqttMapping generated!")
      // create new mapping mo
      const response: IResult<IManagedObject> = await this.inventory.create({
        c8y_mqttMapping: [],
        name: "MQTT-Mapping",
        type: MAPPING_TYPE
      });

      //create identity for mo
      identity = {
        ...identity,
        managedObject: {
          id: response.data.id
        }
      }
      const { data, res } = await this.identity.create(identity);
      this.mappingId = response.data.id;
      // return empty mapping
      return [];
    }
  }

  async saveMappings(mappings: Mapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_mqttMapping: mappings,
      id: this.mappingId,
    });
  }

  private initializeContext(mapping: Mapping): ProcessingContext {
    let ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      needsRepair: false,
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: true
    }
    return ctx;
  }


  private deserializePayload(context: ProcessingContext, mapping: Mapping) {
    context.payload = JSON.parse(mapping.source);
  }

  private extractFromSource(context: ProcessingContext) {
    let mapping: Mapping = context.mapping;
    let payloadJsonNode: JSON = context.payload;
    let postProcessingCache: Map<string, SubstituteValue[]> = context.postProcessingCache;
    let topicLevels = splitTopicExcludingSeparator(context.topic);
    payloadJsonNode[TOKEN_TOPIC_LEVEL] = topicLevels;

    let payload: string = JSON.stringify(payloadJsonNode, null, 4);
    let substitutionTimeExists: boolean = false;

    mapping.substitutions.forEach(substitution => {
      let extractedSourceContent: JSON;
      // step 1 extract content from incoming payload
      extractedSourceContent = this.evaluateExpression(JSON.parse(mapping.source), substitution.pathSource, true);

      //step 2 analyse exctracted content: textual, array
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, substitution.pathTarget, []);
      if (extractedSourceContent == undefined) {
        console.error("No substitution for: ", substitution.pathSource, payload);
        postProcessingCacheEntry.push(
          {
            value: extractedSourceContent,
            type: SubstituteValueType.IGNORE,
            repairStrategy: substitution.repairStrategy
          });
        postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
      } else {
        if (Array.isArray(extractedSourceContent)) {
          if (substitution.expandArray) {
            // extracted result from sourcPayload is an array, so we potentially have to
            // iterate over the result, e.g. creating multiple devices
            extractedSourceContent.forEach(jn => {
              if (isNumeric(jn)) {
                postProcessingCacheEntry
                  .push({
                    value: JSON.parse(jn.toString()),
                    type: SubstituteValueType.NUMBER,
                    repairStrategy: substitution.repairStrategy
                  });
              } else if (whatIsIt(jn) == 'Array') {
                postProcessingCacheEntry
                  .push({
                    value: JSON.parse(jn),
                    type: SubstituteValueType.TEXTUAL,
                    repairStrategy: substitution.repairStrategy
                  });
              } else {
                console.warn(`Since result is not textual or number it is ignored: ${jn}`);
              }
            })
            context.cardinality.set(substitution.pathTarget, extractedSourceContent.length);
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          } else {
            // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE substitution
            context.cardinality.set(substitution.pathTarget, 1);
            postProcessingCacheEntry
              .push({
                value: extractedSourceContent,
                type: SubstituteValueType.ARRAY,
                repairStrategy: substitution.repairStrategy
              })
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          }
        } else if (isNumeric(JSON.stringify(extractedSourceContent))) {
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.NUMBER, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        } else if (whatIsIt(extractedSourceContent) == "String") {
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.TEXTUAL, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        } else {
          console.log(`This substitution, involves an objects for: ${substitution.pathSource}, ${extractedSourceContent}`);
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.OBJECT, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        }
        console.log(`Evaluated substitution (pathSource:substitute)/(${substitution.pathSource}:${extractedSourceContent}), (pathTarget)/(${substitution.pathTarget}`);
      }
      if (substitution.pathTarget === TIME) {
        substitutionTimeExists = true;
      }
    })

    // no substitution for the time property exists, then use the system time
    if (!substitutionTimeExists) {
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, TIME, []);
      postProcessingCacheEntry.push(
        { value: JSON.parse(new Date().toISOString()), type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT });

      postProcessingCache.set(TIME, postProcessingCacheEntry);
    }
  }


  private async substituteInTargetAndSend(context: ProcessingContext) {
    //step 3 replace target with extract content from incoming payload
    let mapping = context.mapping;
    let payloadTarget: JSON = null;
    try {
      payloadTarget = JSON.parse(mapping.target);
    } catch (e) {
      this.alert.warning("Target Payload is not a valid json object!");
      throw e;
    }

    let postProcessingCache: Map<string, SubstituteValue[]> = context.postProcessingCache;
    let maxEntry: string = API[mapping.targetAPI].identifier;
    for (let entry of postProcessingCache.entries()) {
      if (postProcessingCache.get(maxEntry).length < entry[1].length) {
        maxEntry = entry[0];
      }
    }

    let deviceEntries: SubstituteValue[] = postProcessingCache.get(API[mapping.targetAPI].identifier);


    let countMaxlistEntries: number = postProcessingCache.get(maxEntry).length;
    let toDouble: SubstituteValue = deviceEntries[0];
    while (deviceEntries.length < countMaxlistEntries) {
      deviceEntries.push(toDouble);
    }

    let i: number = 0;
    for (let device of deviceEntries) {
      let predecessor: number = -1;
      for (let pathTarget of postProcessingCache.keys()) {
        let substituteValue: SubstituteValue = {
          value: JSON.parse("NOT_DEFINED"),
          type: SubstituteValueType.TEXTUAL,
          repairStrategy: RepairStrategy.DEFAULT
        }
        if (i < postProcessingCache.get(pathTarget).length) {
          substituteValue = _.clone(postProcessingCache.get(pathTarget)[i]);
        } else if (postProcessingCache.get(pathTarget).length == 1) {
          // this is an indication that the substitution is the same for all
          // events/alarms/measurements/inventory
          if (substituteValue.repairStrategy == RepairStrategy.USE_FIRST_VALUE_OF_ARRAY) {
            substituteValue = _.clone(postProcessingCache.get(pathTarget)[0]);
          } else if (substituteValue.repairStrategy == RepairStrategy.USE_LAST_VALUE_OF_ARRAY) {
            let last: number = postProcessingCache.get(pathTarget).length - 1;
            substituteValue = _.clone(postProcessingCache.get(pathTarget)[last]);
          }
          console.warn(`During the processing of this pathTarget: ${pathTarget} a repair strategy: ${substituteValue.repairStrategy} was used!`);
        }

        if (mapping.targetAPI != (API.INVENTORY.name)) {
          if (pathTarget == API[mapping.targetAPI].identifier) {
            let sourceId: string = await resolveExternalId(JSON.stringify(substituteValue.value),
              mapping.externalIdType);
            if (sourceId == null && mapping.createNonExistingDevice) {
              if (context.sendPayload) {
                let d: IManagedObject = await upsertDevice({
                  name: "device_" + mapping.externalIdType + "_" + JSON.stringify(substituteValue.value),
                  type: MQTT_TEST_DEVICE_TYPE
                }, JSON.stringify(substituteValue.value), mapping.externalIdType);
                substituteValue.value = JSON.parse(d.getId().getValue());
              }

              let map = {
                c8y_IsDevice: null,
                name: "device_" + mapping.externalIdType + "_" + substituteValue.value
              }
              context.requests.push(
                {
                  predecessor: predecessor,
                  method: "PATCH",
                  source: JSON.stringify(device.value),
                  externalIdType: mapping.externalIdType,
                  payload: JSON.stringify(map),
                  targetAPI: API.INVENTORY.name,
                  error: null,
                  postProcessingCache: postProcessingCache
                });
              predecessor = context.requests.length;

            } else if (sourceId == null) {
              throw new Error("External id " + substituteValue + " for type "
                + mapping.externalIdType + " not found!");
            } else {
              substituteValue.value = JSON.parse(sourceId);
            }
          }
          _.set(payloadTarget, pathTarget, substituteValue.value)
        } else if (pathTarget != (API[mapping.targetAPI].identifier)) {
          _.set(payloadTarget, pathTarget, substituteValue.value)

        }
      }
      /*
       * step 4 prepare target payload for sending to c8y
       */
      if (mapping.targetAPI == API.INVENTORY.name) {
        let ex: Error = null;
        if (context.sendPayload) {
          try {
            upsertDevice(payloadTarget, JSON.stringify(device.value), mapping.externalIdType);
          } catch (e) {
            ex = e;
          }
        }
        context.requests.push(
          {
            predecessor: predecessor,
            method: "PATCH",
            source: JSON.stringify(device.value),
            externalIdType: mapping.externalIdType,
            payload: JSON.stringify(payloadTarget),
            targetAPI: API.INVENTORY.name,
            error: ex,
            postProcessingCache: postProcessingCache
          });
        predecessor = context.requests.length;

      } else if (mapping.targetAPI == (API.INVENTORY.name)) {
        let ex: Error = null;
        if (context.sendPayload) {
          try {
            createMEAO(mapping.targetAPI, payloadTarget, mapping);
          } catch (e) {
            ex = e;
          }
        }
        context.requests.push(
          {
            predecessor: predecessor,
            method: "POST",
            source: JSON.stringify(device.value),
            externalIdType: mapping.externalIdType,
            payload: JSON.stringify(payloadTarget),
            targetAPI: API.INVENTORY.name,
            error: ex,
            postProcessingCache: postProcessingCache
          })
        predecessor = context.requests.length;
      } else {
        console.warn("Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${postProcessingCache.size}", mapping.targetAPI,
          postProcessingCache.size);
      }
      console.log(`Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`, payloadTarget, mapping.targetAPI);
      i++;
    }
  }

  async testResult(mapping: Mapping, simulation: boolean): Promise<any> {
    let context = this.initializeContext(mapping);
    this.deserializePayload(context, mapping);
    this.extractFromSource(context);
    this.substituteInTargetAndSend(context);
    let result = JSON.parse(mapping.target);
    let substitutionTimeExists = false;
    if (!this.testDeviceId) {
      console.error("Need to intialize MQTT test device:", this.testDeviceId);
      result = mapping.target;
    } else {
      console.log("MQTT test device is already initialized:", this.testDeviceId);
      mapping.substitutions.forEach(sub => {
        console.log("Looking substitution for:", sub.pathSource, mapping.source, result);
        let s: JSON
        if (sub.pathTarget == API[mapping.targetAPI].identifier) {
          s = this.testDeviceId as any;
        } else {
          s = this.evaluateExpression(JSON.parse(mapping.source), sub.pathSource, true);
          if (s == undefined) {
            console.error("No substitution for:", sub.pathSource, s, mapping.source);
            this.alert.warning("Warning: no substitution found for : " + sub.pathSource)
            //throw Error("Error: substitution not found:" + sub.pathSource);
          }
        }
        _.set(result, sub.pathTarget, s)

        if (sub.pathTarget == TIME) {
          substitutionTimeExists = true;
        }
      })

      // no substitution fot the time property exists, then use the system time
      if (!substitutionTimeExists) {
        result.time = new Date().toISOString();
      }
    }

    // The producing code (this may take some time)
    return result;
  }

  async sendTestResult(mapping: Mapping): Promise<string> {
    let result: any;
    let test_payload = await this.testResult(mapping, true);
    let error: string = '';

    if (mapping.targetAPI == API.EVENT.name) {
      let p: IEvent = test_payload as IEvent;
      if (p != null) {
        result = this.event.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else if (mapping.targetAPI == API.ALARM.name) {
      let p: IAlarm = test_payload as IAlarm;
      if (p != null) {
        result = this.alarm.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else if (mapping.targetAPI == API.MEASUREMENT.name) {
      let p: IMeasurement = test_payload as IMeasurement;
      if (p != null) {
        result = this.measurement.create(p);
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    } else {
      let p: IManagedObject = test_payload as IManagedObject;
      if (p != null) {
        if (mapping.updateExistingDevice) {
          result = this.inventory.update(p);
        } else {
          result = this.inventory.create(p);
        }
      } else {
        error = "Payload is not a valid:" + mapping.targetAPI;
      }
    }

    if (error != '') {
      this.alert.danger("Failed to tested mapping: " + error);
      return '';
    }

    try {
      let { data, res } = await result;
      //console.log ("My data:", data );
      if ((res.status == 200 || res.status == 201)) {
        this.alert.success("Successfully tested mapping!");
        return data;
      } else {
        let e = await res.text();
        this.alert.danger("Failed to tested mapping: " + e);
        return '';
      }
    } catch (e) {
      let { data, res } = await e;
      this.alert.danger("Failed to tested mapping: " + data.message);
      return '';
    }

  }

  public evaluateExpression(json: JSON, path: string, flat: boolean): JSON {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path)
      result = expression.evaluate(json) as JSON
      // if (flat) {
      //   if (Array.isArray(result)) {
      //     result = result[0];
      //   }
      // } else {
      //   result = JSON.stringify(result, null, 4);
      // }
    }
    return result;
  }
}

async function resolveExternalId(externalId: string, externalIdType: string): Promise<string> {
  let identity: IExternalIdentity = {
    type: externalIdType,
    externalId: externalId
  };
  try {
    const { data, res } = await this.identity.detail(identity);
    this.mappingId = data.managedObject.id as string;
    const response: IResult<IManagedObject> = await this.inventory.detail(this.mappingId);
    return data.managedObject.id as string;
  } catch (e) {
    console.log(`External id ${externalId} doesn't exist!`);
    return;
  }

}
async function upsertDevice(payload: any, externalId: string, externalIdType: string): Promise<IManagedObject> {
  let deviceId: string = await this.resolveExternalId(externalId, externalIdType);
  let device: Partial<IManagedObject> = {
    ...payload,
    c8y_IsDevice: {},
    c8y_mqttMapping_TestDevice: {},
    com_cumulocity_model_Agent: {}
  }
  if (deviceId) {
    const response: IResult<IManagedObject> = await this.inventory.update(device);
    return response.data;
  } else {
    const response: IResult<IManagedObject> = await this.inventory.create(device);
    //create identity for mo
    let identity = {
      type: externalIdType,
      externalId: externalId,
      managedObject: {
        id: response.data.id
      }
    }
    const { data, res } = await this.identity.create(identity);
    return response.data;
  }
}

function createMEAO(targetAPI: string, payloadTarget: JSON, mapping:Mapping) {
  let result: any;
  let error: string = '';
  if (targetAPI == API.EVENT.name) {
    let p: IEvent = payloadTarget as any;
    if (p != null) {
      result = this.event.create(p);
    } else {
      error = "Payload is not a valid:" + targetAPI;
    }
  } else if (targetAPI == API.ALARM.name) {
    let p: IAlarm = payloadTarget as any;
    if (p != null) {
      result = this.alarm.create(p);
    } else {
      error = "Payload is not a valid:" + targetAPI;
    }
  } else if (targetAPI == API.MEASUREMENT.name) {
    let p: IMeasurement = payloadTarget as any;
    if (p != null) {
      result = this.measurement.create(p);
    } else {
      error = "Payload is not a valid:" + targetAPI;
    }
  } else {
    let p: IManagedObject = payloadTarget as any;
    if (p != null) {
      if (mapping.updateExistingDevice) {
        result = this.inventory.update(p);
      } else {
        result = this.inventory.create(p);
      }
    } else {
      error = "Payload is not a valid:" + targetAPI;
    }
  }
}

