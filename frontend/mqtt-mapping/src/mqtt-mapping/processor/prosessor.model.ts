import { Mapping, MappingType, RepairStrategy } from "../../shared/mapping.model";

export interface C8YRequest {
    predecessor?: number;
    method?: string;
    source?: any;
    externalIdType?: string;
    request?: any;
    response?: any;
    targetAPI?: string;
    error?: Error;
}

export interface ProcessingContext {
    mapping: Mapping;
    topic: string;
    payload?: JSON;
    requests?: C8YRequest[];
    processingType?: ProcessingType;
    cardinality: Map<string, number>;
    mappingType: MappingType;
    postProcessingCache: Map<string, SubstituteValue[]>;
    sendPayload?: boolean;
}

export enum ProcessingType {
    UNDEFINED,
    ONE_DEVICE_ONE_VALUE,
    ONE_DEVICE_MULTIPLE_VALUE,
    MULTIPLE_DEVICE_ONE_VALUE,
    MULTIPLE_DEVICE_MULTIPLE_VALUE,
}

export enum SubstituteValueType {
    NUMBER,
    TEXTUAL,
    OBJECT,
    IGNORE,
    ARRAY
}

export interface SubstituteValue {
    value: any;
    type: SubstituteValueType;
    repairStrategy: RepairStrategy
}