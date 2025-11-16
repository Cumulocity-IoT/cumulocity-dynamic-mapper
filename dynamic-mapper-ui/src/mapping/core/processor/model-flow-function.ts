/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

/** Details of external Id (which will be looked up by IdP to get the C8Y id, and optionally used to create a device). */
export interface ExternalSource {
    /** External Id to be looked up and/or created to get C8Y "id" */
    externalId: string;

    /** e.g. "c8y_Serial"  */
    type: string;

    // Advanced:

    /** default true (false for advanced users, e.g. if they want to create somewhere deeper in the hierarchy) */
    autoCreateDeviceMO?: boolean;

    // TODO: more design needed for complex onboarding requirements - e.g. for advanced hierarchy changes, send separate messages (similar to the C8Y Rest API) or include those instructions as part of ExternalSource? Also need more fields for setting fragments on new MOs etc
    /** To support adding child assets/devices */
    parentId?: string;
    /** If creating a child, what kind to create */
    childReference?: "device" | "asset" | "addition";

    /** Transport/MQTT client Id, stored on the MO first time/creation, so it can be used in outbound messages to send to the device. Would be stashed on the MO for inbound messages so that we can read it on the outbound side for use by the device */
    clientId?: string;
}

/** Details of external Id (which will be looked up by IdP to get the C8Y id, and optionally used to create a device). */
export interface ExternalId {
    /** External Id to be looked up and/or created to get C8Y "id" */
    externalId: string;

    /** e.g. "c8y_Serial"  */
    type: string;
}


/** A request going to or coming from Cumulocity core (or IceFlow/offloading) */
export interface CumulocityObject {
    /** The same payload that would be used in the C8Y REST/SmartREST API 
    Exceptions:
     - If providing an externalSource you don't need to provide an "id" as you would in those APIs. 
     - When using update APIs (e.g. PUT /inventory/managedObjects/{id}), you should add an "id" field into the payload (which would otherwise be in the "path")
*/
    payload: object;

    /** Which type in the C8Y api is being modified. Singular not plural. e.g. "measurement". The presence of this field also serves as a discriminator to identify this JS object as CumulocityObject */
    cumulocityType: string;
    /** What kind of operation is being performed on this type */
    action: "create" | "update";

    // nb: we also considered using the JSON via MQTT SmartREST format 
    // e.g. request='measurement/measurements/create' but it's a bit too
    // unintuitive, or to use the tedge topic/channel concept but 
    // since there is no topic really involved that's also undesirable

    /** Since we usually don't know the C8Y Id to put in the payload, 
    the flow can specify a single external Id to lookup (and optionally create). 
    Mandatory to include one item when sending this from cloud. Optional for thin-edge. 
    
    When a Cumulocity message (e.g. operation) is received, this will contain a list of all external ids for this Cumulocity id.
    */
    externalSource?: ExternalId[] | ExternalId;

    // For advanced cases only:

    /** For messages sent by the flow, this is "cumulocity" by default, but can be set to other options for other destinations. 
For messages received by the flow this is not set.
(nb: no need for a "processingMode" option due to having this) */
    destination?: "cumulocity" | "iceflow" | "streaming-analytics";
}

/** 
 * A (Pulsar) message received from a device or sent to a device.
 * 
 * @example
 * // Basic usage with device ID in topic
 * return [{  
 *     topic: `measurements/${payload["source"]["id"]}`,
 *     payload: {
 *         "time": new Date().toISOString(),
 *         "c8y_Steam": {
 *             "Temperature": {
 *                 "unit": "C",
 *                 "value": 25.5
 *             }
 *         }
 *     },
 *     transportFields: { "key": payload["source"]["id"] }
 * }];
 * 
 * @example
 * // Using _externalId_ placeholder in topic
 * // The _externalId_ placeholder is automatically resolved using the externalId type
 * // from the externalSource array
 * return [{  
 *     topic: `measurements/_externalId_`,
 *     payload: {
 *         "time": new Date().toISOString(),
 *         "c8y_Steam": {
 *             "Temperature": {
 *                 "unit": "C",
 *                 "value": 25.5
 *             }
 *         }
 *     },
 *     transportFields: { "key": payload["source"]["id"] },
 *     externalSource: [{"type": "c8y_Serial"}]  // Defines which externalId type to use
 * }];
 */
export interface DeviceMessage {
    /** 
     * Cloud IdP and first step of tedge always gets an ArrayBuffer, but might be a JS object 
     * if passing intermediate messages between steps in thin-edge.
     */
    payload: Uint8Array;

    /** 
     * Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc.
     * Mandatory unless in thin-edge (when it can be inferred from context).
     */
    transportId?: string;

    /** 
     * The topic on the transport (e.g. MQTT topic).
     * 
     * Special placeholder: Use `_externalId_` in the topic to automatically reference 
     * the external ID of the device. The placeholder will be resolved using the 
     * externalId type specified in the `externalSource` field.
     * 
     * @example "measurements/_externalId_"
     * @example "measurements/12345"
     */
    topic: string;

    /** 
     * Transport/MQTT client Id.
     * DiM team just renamed this from clientId->client, but we feel clientId is clearer; 
     * will discuss with them when Scott is back.
     * Mandatory unless in thin-edge (when it can be inferred from context).
     */
    clientId?: string;

    /** 
     * Dictionary of transport/MQTT-specific fields/properties/headers.
     * For Kafka, use "key" to define the record key.
     * 
     * @example { "key": "device-123" }
     */
    transportFields?: { [key: string]: any };

    /** 
     * Timestamp of incoming Pulsar message; does nothing when sending.
     */
    time?: Date;

    /**
     * External source configuration for resolving the `_externalId_` placeholder in the topic.
     * Defines which external ID type should be used to lookup the device.
     * 
     * @example [{"type": "c8y_Serial"}]
     * @example [{"type": "c8y_DeviceId"}]
     */
    externalSource?: Array<{ type: string }>;
}

export interface DataPrepContext {
    /**
    * Sets a value in the context's state.
    * @param {string} key - The key for the state item.
    * @param {any} value - The value to set for the given key.
    */
    setState(key: string, value: any): void;

    /**
     * Retrieves a value from the context's state.
     * @param {string} key - The key of the state item to retrieve.
     * @returns {any} The value associated with the key, or undefined if not found.
     */
    getState(key: string): any;

    /**
     * Retrieves the entire configuration map for the context.
     * @returns {Record<string, any>} A Map containing the context's configuration.
     */
    getConfig(): Record<string, any>;

    /**
     * Lookup DTM Asset properties by asset ID.
     * @param {string} assetId - The ID of the asset to look up.
     * @returns {Record<string, any>} A record containing the asset properties.
     */
    getDTMAsset(assetId: string): Record<string, any>;

    /**
     * Lookup a device from the inventory cache by device ID.
     * @param {string} deviceId - The internal device ID to look up.
     * @returns {any} The managed object from inventory, or null if not found.
     */
    getManagedObjectByDeviceId(deviceId: string): any;

    /**
     * Lookup a device from the inventory cache by external ID.
     * @param {string} externalId - The external ID to look up.
     * @returns {any} The managed object from inventory, or null if not found.
     */
    getManagedObject(externalId: ExternalId): any;
}

export interface InputMessage {
    /**
    * An unique source path, example: MQTT Topic.
    * @type {string}
    */
    sourcePath: string;

    /**
    * The source id, example: MQTT client id.
    * @type {string}
    */
    sourceId: string;

    /**
    * The payload of the message.
    * @type {any}
    */
    payload: any;

    /**
     * A map of properties associated with the message, represented as a generic JSON object.
     * @type {Record<string, any>}
     */
    properties: Record<string, any>;
}

export interface OutputMessage {
    /**
    * An unique sink type, example: C8Y Core.
    * @type {string}
    */
    sinkType: string;

    /**
    * The unique device identifier, example: External Id.
    * @type {string}
    */
    deviceIdentifier?: Record<string, any>;

    /**
    * The payload of the message.
    * @type {any}
    */
    payload: any;

    /**
     * A map of properties associated with the message, represented as a generic JSON object.
     * @type {Record<string, any>}
     */
    properties: Record<string, any>;
}


export interface MappingError {
    errorDetails: string[];

    /**
     * Optional payload that resulted in this error
     * @type {any}
     */
    payload?: any;
}
