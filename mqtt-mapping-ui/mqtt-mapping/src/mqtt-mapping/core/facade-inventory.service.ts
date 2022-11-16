import { Injectable } from "@angular/core";
import { IManagedObject, InventoryService, IResult } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";
import { MockInventoryService } from "./mock/mock-inventory.service";

@Injectable({ providedIn: 'root' })
export class FacadeInventoryService {

  inventoryCache: Map<string, Map<string, string>>;
  constructor(private mockInventory: MockInventoryService,
    private inventory: InventoryService) {
  }

  public update(managedObject: Partial<IManagedObject>, context: ProcessingContext): Promise<IResult<IManagedObject>> {
    if (context.sendPayload) {
      return this.inventory.update(managedObject);
    } else {
      return this.mockInventory.update(managedObject);
    }
  }

  public create(managedObject: Partial<IManagedObject>, context: ProcessingContext): Promise<IResult<IManagedObject>> {
    if (context.sendPayload) {
      return this.inventory.create(managedObject);
    } else {
      return this.mockInventory.create(managedObject);
    }
  }

}