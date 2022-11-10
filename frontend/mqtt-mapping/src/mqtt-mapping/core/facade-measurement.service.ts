import { Injectable } from "@angular/core";
import { IMeasurement, IResult, MeasurementService } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";

@Injectable({ providedIn: 'root' })
export class FacadeMeasurementService {
  constructor(
    private measurement: MeasurementService) {
  }

  public create(measurement: IMeasurement, context: ProcessingContext): Promise<IResult<IMeasurement>> {
    if (context.sendPayload) {
      return this.measurement.create(measurement);
    } else {
      let copyMeasurement: IMeasurement = {
        ...measurement,
        id: Math.floor(100000 + Math.random() * 900000).toString(),
        lastUpdated: new Date().toISOString(),
      }
      const promise = Promise.resolve({
        data: copyMeasurement,
        res: {status: 200}
      });
      return promise;
    }
  }
}