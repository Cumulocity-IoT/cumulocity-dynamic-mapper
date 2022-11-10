import { Injectable } from "@angular/core";
import { IManagedObject, IResult } from "@c8y/client";
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class MockInventoryService  {
  
  inventoryCache: Map<string, Map<string,any>>;
  constructor() {
    this.initializeCache();
   }

  public initializeCache(): void {
    this.inventoryCache = new Map<string, Map<string,IManagedObject>>();
  }
  
  public update(managedObject: Partial<IManagedObject>): Promise<IResult<IManagedObject>>{
    let copyManagedObject: Partial<IManagedObject> = _.clone(managedObject);
    copyManagedObject = {
      ...this.inventoryCache.get(managedObject.id),
      lastUpdated: new Date().toISOString(),
    }
    copyManagedObject.lastUpdated = new Date().toISOString();
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: {status: 200}
    });
    return promise;
  }

  public create(managedObject: Partial<IManagedObject>): Promise<IResult<IManagedObject>> {
    let copyManagedObject = {
      ...managedObject,
      id: Math.floor(100000 + Math.random() * 900000).toString(),
      lastUpdated: new Date().toISOString(),
    }
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: {status: 200}
    });
    return promise;
  }

}