import { Injectable } from "@angular/core";
import { IExternalIdentity, IFetchResponse, IIdentified, IResult } from "@c8y/client";
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class  MockIdentityService {

  identityCache: Map<string, Map<string, IIdentified>>;

  constructor() { 
    this.initializeCache();

  }
  public initializeCache(): void {
    this.identityCache = new Map<string, Map<string, IIdentified>>();
  }

  public detail(identity: IExternalIdentity): Promise<IResult<IExternalIdentity>> {
    let externalIds = this.identityCache.get(identity.type);
    if (externalIds) {
      let externalId: IIdentified = externalIds.get(identity.externalId);
      if (externalId) {
        const copyExternalIdentity: IExternalIdentity = _.clone(identity);
        copyExternalIdentity.managedObject = externalId;
        const promise = Promise.resolve({
          data: copyExternalIdentity,
          res: {status: 200} as IFetchResponse
        });
        return promise;
      } else {
        throw new Error (`External id ${identity.externalId} for type ${identity.type} does not exist.`);
      }
    } else {
      throw new Error (`External id ${identity.externalId} for type ${identity.type} does not exist.`);
    }
  }

  public create(identity: IExternalIdentity): Promise<IResult<IExternalIdentity>> {

    let id : number =  Math.floor(100000 + Math.random() * 900000);
    let identified: IIdentified = { id: id };

    let externalIds = this.identityCache.get(identity.type);
    if (!externalIds) {
      externalIds = new Map<string, IIdentified>();
      externalIds.set(identity.externalId, identified);
      this.identityCache.set(identity.type, externalIds);
    } else {
      let sourceID = externalIds.get(identity.externalId);
      if (sourceID){
        throw new Error (`External id ${identity.externalId} for type ${identity.type} already exists.`);
      }
      externalIds.set(identity.externalId,identified);
    }
    const copyExternalIdentity: IExternalIdentity = _.clone(identity);
    copyExternalIdentity.managedObject = identified;
    const promise = Promise.resolve({
      data: copyExternalIdentity,
      res: {status: 200} as IFetchResponse
    });
    return promise;
  }

}