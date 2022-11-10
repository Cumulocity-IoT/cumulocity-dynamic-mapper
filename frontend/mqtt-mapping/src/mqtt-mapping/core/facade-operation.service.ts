import { Injectable } from "@angular/core";
import { IOperation, IResult, OperationService } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";

@Injectable({ providedIn: 'root' })
export class FacadeOperationService {
  constructor(
    private operation: OperationService) {
  }

  public create(operation: IOperation, context: ProcessingContext): Promise<IResult<IOperation>> {
    if (context.sendPayload) {
      return this.operation.create(operation);
    } else {
      let copyOperation = {
        ...operation,
        id: Math.floor(100000 + Math.random() * 900000).toString(),
        lastUpdated: new Date().toISOString(),
      }
      const promise = Promise.resolve({
        data: copyOperation,
        res: {status: 200}
      });
      return promise;
    }
  }
}