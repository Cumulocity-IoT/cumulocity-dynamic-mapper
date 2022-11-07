import { Pipe, PipeTransform } from "@angular/core";
import { Mapping } from "src/shared/configuration.model";
import { definesDeviceIdentifier } from "../../../shared/helper";

@Pipe({ name: 'countDeviceIdentifers',
pure: true})

export class CountDeviceIdentifiersPipe implements PipeTransform {
    transform(mapping: Mapping, ...args: any[]) {
        return mapping.substitutions.filter(sub => definesDeviceIdentifier(mapping.targetAPI, sub)).length
    }
}