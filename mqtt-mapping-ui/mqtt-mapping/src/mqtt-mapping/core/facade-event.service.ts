import { Injectable } from "@angular/core";
import { IEvent, IResult, EventService, IFetchResponse } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";

@Injectable({ providedIn: 'root' })
export class FacadeEventService {
  constructor(
    private event: EventService) {
  }

  public create(event: IEvent, context: ProcessingContext): Promise<IResult<IEvent>> {
    if (context.sendPayload) {
      return this.event.create(event);
    } else {
      let copyEvent = {
        ...event,
        id: Math.floor(100000 + Math.random() * 900000).toString(),
        lastUpdated: new Date().toISOString(),
      }
      const promise = Promise.resolve({
        data: copyEvent,
        res: {status: 200} as IFetchResponse
      });
      return promise;
    }
  }
}