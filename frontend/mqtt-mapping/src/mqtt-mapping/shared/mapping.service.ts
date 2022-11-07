import { Injectable } from '@angular/core';
import { AlarmService, EventService, IAlarm, IdentityService, IEvent, IExternalIdentity, IManagedObject, IMeasurement, InventoryService, IResult, MeasurementService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { API, C8YRequest, Mapping, ProcessingContext, ProcessingType, RepairStrategy, SubstituteValue, SubstituteValueType } from '../../shared/configuration.model';
import { isNumeric, MAPPING_FRAGMENT, MAPPING_TYPE, MQTT_TEST_DEVICE_TYPE, returnTypedValue, splitTopicExcludingSeparator, TIME, TOKEN_TOPIC_LEVEL, whatIsIt } from '../../shared/helper';

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

  private initializeContext(mapping: Mapping, sendPayload: boolean): ProcessingContext {
    let ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      needsRepair: false,
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: sendPayload,
      requests: []
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
                    value: jn.toString(),
                    type: SubstituteValueType.NUMBER,
                    repairStrategy: substitution.repairStrategy
                  });
              } else if (whatIsIt(jn) == 'String') {
                postProcessingCacheEntry
                  .push({
                    value: jn,
                    type: SubstituteValueType.TEXTUAL,
                    repairStrategy: substitution.repairStrategy
                  });
              } else if (whatIsIt(jn) == 'Array') {
                postProcessingCacheEntry
                  .push({
                    value: jn,
                    type: SubstituteValueType.ARRAY,
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
    if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY.name) {
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, TIME, []);
      postProcessingCacheEntry.push(
        { value: new Date().toISOString(), type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT });

      postProcessingCache.set(TIME, postProcessingCacheEntry);
    }
  }


  private async substituteInTargetAndSend(context: ProcessingContext) {
    //step 3 replace target with extract content from incoming payload
    let mapping = context.mapping;


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
      let payloadTarget: JSON = null;
      try {
        payloadTarget = JSON.parse(mapping.target);
      } catch (e) {
        this.alert.warning("Target Payload is not a valid json object!");
        throw e;
      }
      for (let pathTarget of postProcessingCache.keys()) {
        let substituteValue: SubstituteValue = {
          value: "NOT_DEFINED" as any,
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
            let sourceId: string = await this.resolveExternalId(substituteValue.value.toString(),
              mapping.externalIdType);
            if (!sourceId && mapping.createNonExistingDevice) {
              let response: IManagedObject = null;

              let map = {
                c8y_IsDevice: {},
                name: "device_" + mapping.externalIdType + "_" + substituteValue.value,
                c8y_mqttMapping_Generated_Type: {},
                c8y_mqttMapping_TestDevice: {},
                type: MQTT_TEST_DEVICE_TYPE
              }
              if (context.sendPayload) {
                response = await this.upsertDevice(map, substituteValue.value, mapping.externalIdType);
                substituteValue.value = response.id as any;
              }
              context.requests.push(
                {
                  predecessor: predecessor,
                  method: "PATCH",
                  source: device.value,
                  externalIdType: mapping.externalIdType,
                  request: map,
                  response: response,
                  targetAPI: API.INVENTORY.name,
                  error: null,
                  postProcessingCache: postProcessingCache
                });
              predecessor = context.requests.length;

            } else if (sourceId == null && context.sendPayload) {
              throw new Error("External id " + substituteValue + " for type "
                + mapping.externalIdType + " not found!");
            } else if (sourceId == null) {
              substituteValue.value = null
            } else {
              substituteValue.value = sourceId.toString();
            }
          }
          if (substituteValue.repairStrategy == RepairStrategy.REMOVE_IF_MISSING && !substituteValue) {
            _.unset(payloadTarget, pathTarget);
          } else {
            _.set(payloadTarget, pathTarget, returnTypedValue(substituteValue))
          }
        } else if (pathTarget != (API[mapping.targetAPI].identifier)) {
          if (substituteValue.repairStrategy == RepairStrategy.REMOVE_IF_MISSING && !substituteValue) {
            _.unset(payloadTarget, pathTarget);
          } else {
            _.set(payloadTarget, pathTarget, returnTypedValue(substituteValue))
          }

        }
      }
      /*
       * step 4 prepare target payload for sending to c8y
       */
      if (mapping.targetAPI == API.INVENTORY.name) {
        let ex: Error = null;
        let response = null
        if (context.sendPayload) {
          try {
            response = await this.upsertDevice(payloadTarget, JSON.stringify(device.value), mapping.externalIdType);
          } catch (e) {
            ex = e;
          }
        }
        context.requests.push(
          {
            predecessor: predecessor,
            method: "PATCH",
            source: device.value,
            externalIdType: mapping.externalIdType,
            request: payloadTarget,
            response: response,
            targetAPI: API.INVENTORY.name,
            error: ex,
            postProcessingCache: postProcessingCache
          });
        predecessor = context.requests.length;

      } else if (mapping.targetAPI != API.INVENTORY.name) {
        let ex: Error = null;
        let response = null
        if (context.sendPayload) {
          try {
            response = await this.createMEAO(mapping.targetAPI, payloadTarget, mapping);
          } catch (e) {
            ex = e;
          }
        }
        context.requests.push(
          {
            predecessor: predecessor,
            method: "POST",
            source: device.value,
            externalIdType: mapping.externalIdType,
            request: payloadTarget,
            response: response,
            targetAPI: API[mapping.targetAPI].name,
            error: ex,
            postProcessingCache: postProcessingCache
          })
        predecessor = context.requests.length;
      } else {
        console.warn("Ignoring payload: ${payloadTarget}, ${mapping.targetAPI}, ${postProcessingCache.size}");
      }
      console.log(`Added payload for sending: ${payloadTarget}, ${mapping.targetAPI}, numberDevices: ${deviceEntries.length}`);
      i++;
    }
  }

  async testResult(mapping: Mapping, sendPayload: boolean): Promise<C8YRequest[]> {
    let context = this.initializeContext(mapping, sendPayload);
    this.deserializePayload(context, mapping);
    this.extractFromSource(context);
    await this.substituteInTargetAndSend(context);

    // The producing code (this may take some time)
    return context.requests;
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


  async resolveExternalId(externalId: string, externalIdType: string): Promise<string> {
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

  async upsertDevice(payload: any, externalId: string, externalIdType: string): Promise<IManagedObject> {
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

  async createMEAO(targetAPI: string, payloadTarget: JSON, mapping: Mapping) {
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
}