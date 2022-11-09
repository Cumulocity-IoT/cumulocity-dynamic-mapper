import { Injectable } from "@angular/core";
import { IManagedObject, IResult } from "@c8y/client";
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class MockInventoryService  {
  
  identityCache: Map<string, Map<string,string>>;
  constructor() {
    this.initializeCache();
   }

  public initializeCache(): void {
    this.identityCache = new Map<string, Map<string,string>>();
  }
  
  public update(managedObject: Partial<IManagedObject>): Promise<IResult<IManagedObject>>{
    let copyManagedObject: Partial<IManagedObject> = _.clone(managedObject);
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: null
    });
    return promise;
  }

  public create(managedObject: Partial<IManagedObject>): Promise<IResult<IManagedObject>> {
    let copyManagedObject: Partial<IManagedObject> = _.clone(managedObject);
    let id  = Math.floor(100000 + Math.random() * 900000).toString();
    copyManagedObject.id = id;
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: null
    });
    return promise;
  }

}