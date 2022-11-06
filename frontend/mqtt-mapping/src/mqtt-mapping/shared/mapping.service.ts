import { Injectable } from '@angular/core';
import { AlarmService, EventService, IAlarm, IdentityService, IEvent, IExternalIdentity, IManagedObject, IMeasurement, InventoryService, IResult, MeasurementService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { API, Mapping, ProcessingContext, ProcessingType, RepairStrategy, SubstituteValue, SubstituteValueType } from '../../shared/configuration.model';
import { MAPPING_FRAGMENT, MAPPING_TYPE, splitTopicExcludingSeparator, TIME, TOKEN_TOPIC_LEVEL, whatIsIt } from '../../shared/helper';

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



  async initializeContext(mapping: Mapping, context: ProcessingContext) {
    let ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      payload: JSON.parse(mapping.source),
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      needsRepair: false,
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>()
    }
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
              if (isNaN(jn)) {
                postProcessingCacheEntry
                  .push({
                    value: JSON.parse(jn.toString()),
                    type: SubstituteValueType.NUMBER,
                    repairStrategy: substitution.repairStrategy
                  });
              } 
              else if  (isString(jn)) {
                postProcessingCacheEntry
                  .push({
                    value: JSON.parse(jn),
                    type: SubstituteValueType.TEXTUAL,
                    repairStrategy: substitution.repairStrategy
                  });
              }  
              else {
                console.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}",
                  jn.asText());
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
        } else if (isNaN( JSON.stringify(extractedSourceContent))) {
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


  private substituteInTargetAndSend(context: ProcessingContext) {

  }

  async testResult(mapping: Mapping, simulation: boolean): Promise<any> {
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
    let result: Promise<IResult<any>>;
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