import { Injectable } from "@angular/core";
import { IdentityService, IExternalIdentity, IIdentified, InventoryService, IResult } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";
import { MockIdentityService } from "./mock/mock-identity.service";

@Injectable({ providedIn: 'root' })
export class FacadeIdentityService {

  identityCache: Map<string, Map<string, IIdentified>>;

  constructor(private mockIdentity: MockIdentityService,
    private identity: IdentityService) {
  }

  public detail(identity: IExternalIdentity, context: ProcessingContext): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.detail(identity);
    } else {
      return this.mockIdentity.detail(identity);
    }
  }

  public create(identity: IExternalIdentity, context: ProcessingContext): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.create(identity);
    } else {
      return this.mockIdentity.create(identity);
    }
  }
}