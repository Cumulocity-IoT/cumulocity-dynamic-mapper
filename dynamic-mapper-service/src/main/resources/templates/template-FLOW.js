/**
 * @name Flow code
 * @description Definitions used when using flow functions
 * @templateType SYSTEM
 * @defaultTemplate true
 * @internal true
 * @readonly true
 */

// add your shared code relevant for all mappings here ...

/** A request going to or coming from Cumulocity core (or IceFlow/offloading) */
export interface CumulocityMessage {
    /** The same payload that would be used in the C8Y REST/SmartREST API 
    Exceptions:
     - If providing an externalSource you don't need to provide an "id" as you would in those APIs. 
     - When using update APIs (e.g. PUT /inventory/managedObjects/{id}), you should add an "id" field into the payload (which would otherwise be in the "path")
*/
    payload: object;

    /** Which type in the C8Y api is being modified. Singular not plural. e.g. "measurement". The presence of this field also serves as a discriminator to identify this JS object as CumulocityMessage */
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
    externalSource?: ExternalSource[] | ExternalSource;

    internalSource?: CumulocitySource[] | CumulocitySource;

    // For advanced cases only:

    /** For messages sent by the flow, this is "cumulocity" by default, but can be set to other options for other destinations. 
For messages received by the flow this is not set.
(nb: no need for a "processingMode" option due to having this) */
    destination?: "cumulocity" | "iceflow" | "streaming-analytics";
}

/** A (Pulsar) message received from a device or sent to a device */
export interface DeviceMessage {
    /** Cloud IdP and first step of tedge always gets an ArrayBuffer, but might be a JS object if passing intermediate messages between steps in thin-edge */
    payload: ArrayBuffer | object;

    /** Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc.
    Mandatory unless in thin-edge (when it can be inferred from context)
    */
    transportId?: string;
    /** The topic on the transport (e.g. MQTT topic). 
    Unfortunately the DiM team decided to rename this to "channel" instead of "topic" (to avoid confusing between MQTT and Pulsar "topics") but we think calling it topic is better here) */
    topic: string;
    /** Transport/MQTT client Id 
    DiM team just renamed this from clientId->client, but we feel clientId is clearer; will discuss with them when Scott is back
    Mandatory unless in thin-edge (when it can be inferred from context)
    */
    clientId?: string;

    /** Dictionary of transport/MQTT-specific fields/properties/headers */
    transportFields?: { [key: string]: string };

    /** Timestamp of incoming Pulsar message; does nothing when sending */
    time?: Date;
}


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


/** A request going to or coming from Cumulocity core (or IceFlow/offloading) */
export interface CumulocityMessage {
    /** The same payload that would be used in the C8Y REST/SmartREST API 
    Exceptions:
     - If providing an externalSource you don't need to provide an "id" as you would in those APIs. 
     - When using update APIs (e.g. PUT /inventory/managedObjects/{id}), you should add an "id" field into the payload (which would otherwise be in the "path")
*/
    payload: object;

    /** Which type in the C8Y api is being modified. Singular not plural. e.g. "measurement". The presence of this field also serves as a discriminator to identify this JS object as CumulocityMessage */
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
    externalSource?: ExternalSource[] | ExternalSource;

    internalSource?: CumulocitySource[] | CumulocitySource;

    // For advanced cases only:

    /** For messages sent by the flow, this is "cumulocity" by default, but can be set to other options for other destinations. 
For messages received by the flow this is not set.
(nb: no need for a "processingMode" option due to having this) */
    destination?: "cumulocity" | "iceflow" | "streaming-analytics";
}

/** A (Pulsar) message received from a device or sent to a device */
export interface DeviceMessage {
    /** Cloud IdP and first step of tedge always gets an ArrayBuffer, but might be a JS object if passing intermediate messages between steps in thin-edge */
    payload: ArrayBuffer | object;

    /** Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc.
    Mandatory unless in thin-edge (when it can be inferred from context)
    */
    transportId?: string;
    /** The topic on the transport (e.g. MQTT topic). 
    Unfortunately the DiM team decided to rename this to "channel" instead of "topic" (to avoid confusiong between MQTT and Pulsar "topics") but we think calling it topic is better here) */
    topic: string;
    /** Transport/MQTT client Id 
    DiM team just renamed this from clientId->client, but we feel clientId is clearer; will discuss with them when Scott is back
    Mandatory unless in thin-edge (when it can be inferred from context)
    */
    clientId?: string;

    /** Dictionary of transport/MQTT-specific fields/properties/headers */
    transportFields?: { [key: string]: string };

    /** Timestamp of incoming Pulsar message; does nothing when sending */
    time?: Date;
}


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
};


/** Details of external Id (which will be looked up by IdP to get the C8Y id, and optionally used to create a device). */
export interface CumulocitySource {
    /** Cumulocity Id to be looked up and/or created to get C8Y "id" */
    internalId: string;

};

export interface FlowContext {
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
      * Log a message.
      */
    logMessage(msg: any): void;

    /**
     * Lookup DTM Assert properties
     */
    lookupDTMAssetProperties(assetId: string): Record<string, any>;

};

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
