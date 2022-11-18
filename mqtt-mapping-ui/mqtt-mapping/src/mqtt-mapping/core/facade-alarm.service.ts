import { Injectable } from "@angular/core";
import { IAlarm, IResult, AlarmService, IFetchResponse } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";

@Injectable({ providedIn: 'root' })
export class FacadeAlarmService {
  constructor(
    private alarm: AlarmService) {
  }

  public create(alarm: IAlarm, context: ProcessingContext): Promise<IResult<IAlarm>> {
    if (context.sendPayload) {
      return this.alarm.create(alarm);
    } else {
      let copyAlarm = {
        ...alarm,
        id: Math.floor(100000 + Math.random() * 900000).toString(),
        lastUpdated: new Date().toISOString(),
      }
      const promise = Promise.resolve({
        data: copyAlarm,
        res: {status: 200} as IFetchResponse
      });
      return promise;
    }
  }
}