import { IManagedObject } from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { Mapping, API, RepairStrategy } from "../../shared/mapping.model";
import { MQTT_TEST_DEVICE_TYPE } from "../../shared/util";
import { ProcessingContext, SubstituteValue, SubstituteValueType } from "./prosessor.model";
import * as _ from 'lodash';
import { getTypedValue } from "./util";
import { C8YClient } from "./c8y-client.service";
import { Injectable } from "@angular/core";

@Injectable({ providedIn: 'root' })
export abstract class PayloadProcessor {
  constructor(
    private alert: AlertService,
    private c8yClient: C8YClient) { }

  public abstract deserializePayload(context: ProcessingContext, mapping: Mapping): ProcessingContext;

  public abstract extractFromSource(context: ProcessingContext): void;

  protected JSONATA = require("jsonata");


  public async substituteInTargetAndSend(context: ProcessingContext) {
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
            let sourceId: string = await this.c8yClient.resolveExternalId(substituteValue.value.toString(),
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
                response = await this.c8yClient.upsertDevice(map, substituteValue.value, mapping.externalIdType);
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
            _.set(payloadTarget, pathTarget, getTypedValue(substituteValue))
          }
        } else if (pathTarget != (API[mapping.targetAPI].identifier)) {
          if (substituteValue.repairStrategy == RepairStrategy.REMOVE_IF_MISSING && !substituteValue) {
            _.unset(payloadTarget, pathTarget);
          } else {
            _.set(payloadTarget, pathTarget, getTypedValue(substituteValue))
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
            response = await this.c8yClient.upsertDevice(payloadTarget, JSON.stringify(device.value), mapping.externalIdType);
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
            response = await this.c8yClient.createMEAO(mapping.targetAPI, payloadTarget, mapping);
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

}