"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createMockPayload = createMockPayload;
exports.createMockInputMessage = createMockInputMessage;
exports.createMockRuntimeContext = createMockRuntimeContext;
function createMockPayload(data) {
    return {
        ...data,
        get(key) {
            return data[key];
        }
    };
}
function createMockInputMessage(payloadData, topic, messageId) {
    const payload = createMockPayload(payloadData);
    return {
        getPayload: () => payload,
        getTopic: topic ? () => topic : undefined,
        getMessageId: messageId ? () => messageId : undefined
    };
}
function createMockRuntimeContext(options) {
    const state = {};
    return {
        setState(key, value) {
            state[key] = value;
        },
        getState(key) {
            return state[key];
        },
        getStateAll() {
            return { ...state };
        },
        getConfig() {
            return options.config || {};
        },
        getClientId() {
            return options.clientId;
        },
        getManagedObjectByDeviceId(deviceId) {
            return options.devices?.[deviceId] || null;
        },
        getManagedObject(externalId) {
            const key = `${externalId.externalId}:${externalId.type}`;
            return options.externalIdMap?.[key] || null;
        },
        getDTMAsset(assetId) {
            return options.dtmAssets?.[assetId] || {};
        }
    };
}
//# sourceMappingURL=smart-function-runtime.types.js.map