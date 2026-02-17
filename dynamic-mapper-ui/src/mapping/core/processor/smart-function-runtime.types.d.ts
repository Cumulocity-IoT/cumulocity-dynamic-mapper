export interface SmartFunctionPayload {
    [key: string]: any;
    get(key: string): any;
}
export interface SmartFunctionInputMessage {
    getPayload(): SmartFunctionPayload;
    getTopic?(): string;
    getMessageId?(): string;
}
export interface ExternalId {
    externalId: string;
    type: string;
}
export interface SmartFunctionRuntimeContext {
    setState(key: string, value: any): void;
    getState(key: string): any;
    getStateAll(): Record<string, any>;
    getConfig(): Record<string, any>;
    getClientId(): string | undefined;
    getManagedObjectByDeviceId(deviceId: string): any;
    getManagedObject(externalId: ExternalId): any;
    getDTMAsset(assetId: string): Record<string, any>;
}
export interface C8ySourceReference {
    source?: {
        id: string;
        self?: string;
    };
}
export interface C8yMeasurement extends C8ySourceReference {
    id?: string;
    type: string;
    time: string;
    [fragment: string]: any;
}
export interface C8yEvent extends C8ySourceReference {
    id?: string;
    type: string;
    text: string;
    time: string;
    [fragment: string]: any;
}
export type C8yAlarmSeverity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING';
export type C8yAlarmStatus = 'ACTIVE' | 'ACKNOWLEDGED' | 'CLEARED';
export interface C8yAlarm extends C8ySourceReference {
    id?: string;
    type: string;
    text: string;
    severity: C8yAlarmSeverity;
    status: C8yAlarmStatus;
    time: string;
    [fragment: string]: any;
}
export type C8yOperationStatus = 'PENDING' | 'EXECUTING' | 'SUCCESSFUL' | 'FAILED';
export interface C8yOperation {
    id?: string;
    deviceId: string;
    status: C8yOperationStatus;
    description?: string;
    [fragment: string]: any;
}
export interface C8yManagedObject {
    id?: string;
    type?: string;
    name?: string;
    [fragment: string]: any;
}
export interface ExternalSource {
    externalId: string;
    type: string;
    autoCreateDeviceMO?: boolean;
    parentId?: string;
    childReference?: 'device' | 'asset' | 'addition';
    clientId?: string;
}
export interface CumulocityObject {
    payload: object;
    cumulocityType: 'measurement' | 'event' | 'alarm' | 'operation' | 'managedObject';
    action: 'create' | 'update' | 'delete' | 'patch';
    externalSource?: ExternalId[] | ExternalId | ExternalSource[];
    destination?: 'cumulocity' | 'iceflow' | 'streaming-analytics';
    contextData?: object;
    sourceId?: string;
}
export interface DeviceMessage {
    payload: Uint8Array;
    topic: string;
    transportId?: string;
    clientId?: string;
    transportFields?: {
        [key: string]: any;
    };
    time?: Date;
    externalSource?: Array<{
        type: string;
    }>;
    action?: 'create' | 'update' | 'delete' | 'patch';
    cumulocityType?: 'measurement' | 'event' | 'alarm' | 'operation' | 'managedObject';
    sourceId?: string;
}
export type C8yAction = CumulocityObject | DeviceMessage;
export type SmartFunction = (inputMsg: SmartFunctionInputMessage, context: SmartFunctionRuntimeContext) => C8yAction[] | C8yAction | [];
export declare function createMockPayload(data: Record<string, any>): SmartFunctionPayload;
export declare function createMockInputMessage(payloadData: Record<string, any>, topic?: string, messageId?: string): SmartFunctionInputMessage;
export declare function createMockRuntimeContext(options: {
    clientId?: string;
    config?: Record<string, any>;
    devices?: Record<string, any>;
    externalIdMap?: Record<string, any>;
    dtmAssets?: Record<string, any>;
}): SmartFunctionRuntimeContext;
//# sourceMappingURL=smart-function-runtime.types.d.ts.map