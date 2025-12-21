/**
 * @name Sample parsing payload as CSV
 * @description Create measurement parsing payload as CSV
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
*/

/*
Parse multiline CSV payload and create measurements

351144440855493
01/12/2025 15:49:38,0,+021.63,+00002045,+000139.3,-088.7,+000.2,00
01/12/2025 15:54:38,0,+021.63,+00002041,+000139.3,-088.7,+000.3,00
01/12/2025 15:59:38,0,+021.63,+00002042,+000139.3,-088.7,+000.2,00
*/

function onMessage(msg, context) {
    const payload = msg.getPayload();
    console.log("Context: " + context.getConfig());
    console.log("Payload Raw: " + payload);

    // Extract CSV data early
    const csvData = payload["payload"];
    if (!csvData) {
        console.log("No CSV data found in payload");
        return [];
    }

    // Extract device ID
    const deviceId = extractDeviceId(payload, csvData);
    if (!deviceId) {
        console.log("No device ID found");
        return [];
    }
    console.log("Device ID: " + deviceId);

    // Parse CSV and create measurements
    const measurements = parseCSVData(csvData, deviceId);
    console.log("Created " + measurements.length + " measurements");
    
    return measurements;
}

/**
 * Extract device ID from topic or payload
 */
function extractDeviceId(payload, csvData) {
    // Try to get from topic first
    if (payload["_TOPIC_LEVEL_"] && payload["_TOPIC_LEVEL_"][1]) {
        return payload["_TOPIC_LEVEL_"][1];
    }
    
    // Extract from first line of CSV
    const firstLineEnd = csvData.indexOf('\n');
    if (firstLineEnd > 0) {
        return csvData.substring(0, firstLineEnd).trim();
    }
    
    return csvData.trim().split('\n')[0];
}

/**
 * Parse CSV data and create measurements array
 */
function parseCSVData(csvData, deviceId) {
    const lines = csvData.split('\n');
    const measurements = [];
    
    // Skip first line (device ID) and process CSV lines
    for (let i = 1; i < lines.length; i++) {
        const line = lines[i].trim();
        if (!line) continue;
        
        const measurement = parseCSVLine(line, deviceId);
        if (measurement) {
            measurements.push(measurement);
        }
    }
    
    return measurements;
}

/**
 * Parse a single CSV line and create measurement object
 */
function parseCSVLine(line, deviceId) {
    const fields = line.split(',');
    
    // Validate CSV structure
    if (fields.length < 8) {
        console.log("Skipping invalid CSV line (expected 8 fields, got " + fields.length + "): " + line);
        return null;
    }
    
    try {
        // Parse values (remove leading '+' or '-' sign handled by parseFloat)
        const values = [
            parseFloat(fields[2]),
            parseFloat(fields[3]),
            parseFloat(fields[4]),
            parseFloat(fields[5]),
            parseFloat(fields[6])
        ];
        
        // Validate parsed values
        if (values.some(isNaN)) {
            console.log("Skipping line with invalid numeric values: " + line);
            return null;
        }
        
        // Convert timestamp
        const isoTimestamp = convertToISO(fields[0].trim());
        if (!isoTimestamp) {
            console.log("Skipping line with invalid timestamp: " + line);
            return null;
        }
        
        // Create measurement object
        return createMeasurement(isoTimestamp, values, deviceId);
        
    } catch (e) {
        console.log("Error parsing line: " + line + ", Error: " + e.message);
        return null;
    }
}

/**
 * Convert MM/dd/yyyy HH:mm:ss to ISO format
 */
function convertToISO(timestamp) {
    const parts = timestamp.split(' ');
    if (parts.length !== 2) return null;
    
    const dateComponents = parts[0].split('/');
    if (dateComponents.length !== 3) return null;
    
    const month = dateComponents[0].padStart(2, '0');
    const day = dateComponents[1].padStart(2, '0');
    const year = dateComponents[2];
    const time = parts[1];
    
    return year + '-' + month + '-' + day + 'T' + time + '.000Z';
}

/**
 * Create measurement object
 */
function createMeasurement(timestamp, values, deviceId) {
    return {
        cumulocityType: "measurement",
        action: "create",
        payload: {
            time: timestamp,
            type: "c8y_CustomMeasurement",
            c8y_CustomMeasurement: {
                Value1: { value: values[0] },
                Value2: { value: values[1] },
                Value3: { value: values[2] },
                Value4: { value: values[3] },
                Value5: { value: values[4] }
            }
        },
        externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
    };
}