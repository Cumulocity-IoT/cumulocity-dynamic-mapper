import { Injectable } from '@angular/core';
import { IManagedObject, InventoryService, IResult, IResultList } from '@c8y/client';
import { MQTTMapping } from '../mqtt-configuration.model';

@Injectable({ providedIn: 'root' })
export class MQTTMappingService {

  constructor( public inventory: InventoryService,) {}
  mappingId : string;

  private readonly MAPPING_TYPE = 'c8y_mqttMapping_type';

  private readonly MAPPING_FRAGMENT = 'c8y_mqttMapping';

  async loadMappings(): Promise<MQTTMapping[]> {
    const filter: object = {
         pageSize: 100,
         withTotalPages: true
       };
    
    const query = {
        type: this.MAPPING_TYPE
    }
    const response : IResultList<IManagedObject> = await this.inventory.listQuery(query, filter);
    if ( response.data && response.data.length > 0) {
      this.mappingId = response.data[0].id;
      console.log("Found mqtt mapping:", this.mappingId)
      return response.data[0][this.MAPPING_FRAGMENT] as MQTTMapping[];
    } else {
      console.log("No mqtt mapping found!")
      return undefined;
    }
  }

  async saveMappings(mappings: MQTTMapping[]): Promise<IResult<IManagedObject>> {
    return this.inventory.update({
      c8y_MQTTMapping: mappings,
      id: this.mappingId,
    });
  }
}
