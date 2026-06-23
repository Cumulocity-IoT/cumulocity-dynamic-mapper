/**
 * @name Default template for SparkPlug B outbound (NCMD)
 * @description Sends a SparkPlug B NCMD (Node Command) to an edge node.
 *              The payload returned from this function is serialized to
 *              SparkPlug B protobuf binary by the mapper before publishing.
 * @templateType OUTBOUND_SMART_FUNCTION
 * @mappingType SPARKPLUGB
 * @defaultTemplate true
 * @internal true
 * @readonly true
 *
 * Expected use-case:
 *   A Cumulocity operation (e.g. c8y_Command) triggers an NCMD message to the
 *   edge node that owns the device.  The `externalId` is expected to have the
 *   form "GroupID_EdgeNodeID" (set by the mapper from the device's identity
 *   when 'useExternalId' and 'externalIdType' are configured on the mapping).
 *
 * SparkPlug B topic structure: spBv1.0/<GroupID>/<MessageType>/<EdgeNodeID>[/<DeviceID>]
 *
 * Supported metric types (case-insensitive):
 *   Int8, Int16, Int32, Int64, UInt8, UInt16, UInt32, UInt64,
 *   Float, Double, Boolean, String, DateTime, Text, UUID, Bytes
 *
 * context.getConfig().aliasMap  — Map of metric name → alias string loaded from the
 *   NBIRTH/DBIRTH fragment stored on the managed object.  Include the alias in metrics
 *   so that the receiving edge node can match them by alias instead of name (more compact
 *   SparkPlug B messages).  The alias is optional; edge nodes must still accept name-only
 *   metrics according to the specification.
 *
 * context.getConfig().isActive  — Boolean flag reflecting the current online/offline state
 *   of the target Edge Node or Device (true after NBIRTH/NDATA, false after NDEATH).
 *   Return null from this function to suppress the command when the device is offline.
 */

function onMessage(msg, context) {
    var payload = msg.payload;

    // context.getExternalId() returns the resolved external id of the source device.
    // For SparkPlug B edge nodes the expected format is "GroupID_EdgeNodeID".
    // Requires the mapping to have 'useExternalId' enabled and 'externalIdType' configured.
    const externalId = context.getExternalId();

    // Split the external id into group and edge node parts on the FIRST underscore.
    // SparkPlugBDeserializer stores the key as groupId + "_" + edgeNodeId, so splitting
    // on the first '_' is the correct inverse even when edgeNodeId itself contains '_'.
    const firstUnderscore = externalId ? externalId.indexOf('_') : -1;
    const groupId    = firstUnderscore >= 0 ? externalId.substring(0, firstUnderscore) : (externalId || 'DefaultGroup');
    const edgeNodeId = firstUnderscore >= 0 ? externalId.substring(firstUnderscore + 1) : 'DefaultNode';

    // Suppress the command when the edge node has gone offline (NDEATH received).
    if (!context.getConfig().isActive) {
        return null;
    }

    // aliasMap contains metric name → alias (string) entries loaded from the NBIRTH/DBIRTH
    // fragment on the device managed object.  Use it to populate the optional alias field.
    const aliasMap = context.getConfig().aliasMap || {};

    // Helper: build a metric entry, adding the alias when available
    function metric(name, type, value) {
        const entry = { name, type, value };
        if (aliasMap[name] !== undefined) {
            entry.alias = parseInt(aliasMap[name], 10);
        }
        return entry;
    }

    return {
        // NCMD topic: spBv1.0/<GroupID>/NCMD/<EdgeNodeID>
        topic: `spBv1.0/${groupId}/NCMD/${edgeNodeId}`,
        payload: {
            // Optional: include a sequence number (0–255).  Omit to let the mapper use 0.
            // seq: 0,
            timestamp: Date.now(),
            metrics: [
                metric('Node Control/Rebirth', 'Boolean', false)
            ]
        }
    };
}

export {onMessage};
