/*
This sample uses a lora decoder written in javascript, see https://wika-group.github.io/javascript_parsers/packages/parsers/src/PGW23_100_11
for a Wika pressure gauge.
The device connect over LoRaWAN to Cumulocity.
*/


/*&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&#    &&&   
&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&#    &&&   
&&&                                                                                               &&#    &&&   
&&&    .&&&&&      .&&&&&      &&&&&&    &&&&&     .&&&&/     .&&&&&&          &&&&&&&&           &&#    &&&   
&&&     &&&&&&     &&&&&&&     &&&&&     &&&&&     .&&&&/   &&&&&&&           &&&&&&&&&&          &&#    &&&   
&&&      &&&&&(   &&&&&&&&#   &&&&&(     &&&&&     .&&&&/ &&&&&&             &&&&& /&&&&&         &&#    &&&   
&&&       &&&&&  /&&&&,&&&&  ,&&&&&      &&&&&     .&&&&&&&&&&&             &&&&&   &&&&&%        &&#    &&&   
&&&       %&&&&& &&&&  &&&&& &&&&&       &&&&&     .&&&&&&&&&&&&&          &&&&&%    &&&&&(       &&#    &&&   
&&&        &&&&&&&&&(   &&&&&&&&&.       &&&&&     .&&&&&   &&&&&&#       &&&&&&&&&&&&&&&&&.      &&#    &&&   
&&&         &&&&&&&&     &&&&&&&&        &&&&&     .&&&&/     &&&&&&     *&&&&&        &&&&&      &&#    &&&   
&&&          &&&&&&      /&&&&&&         &&&&&     .&&&&/       &&&&&&   &&&&&         #&&&&&     &&#    &&&   
&&&                                                                                                      &&&   
&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&   
&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&*/

/**
 * General information:   
 * This JavaScript-based payload formatter is a parser to decode data from bytes into
 * JSON Object. It can only parse payload from PGW23.100.11 devices. 
 * This parser follows the specification LoRaWAN® Payload Codec API Specification TS013-1.0.0.
 * 
 * 
 * SPDX-FileCopyrightText: Copyright (C) 2023 WIKA Alexander Wiegand SE & Co. KG   
 * SPDX-License-Identifier: MIT
 * 
 * SPDX-FileName: index.js
 * SPDX-PackageVersion: 2.0.0
 *  
*/

// ***********************************************************************************
// Public Decoding Section
// ***********************************************************************************

/**
 * ATTENTION: You must define the measurement ranges first, otherwise the script will not work.
 * The device configuration defines the measurement ranges for the supported measured variables of your used devices, e.g.
 * var PRESSURE_RANGE_START = 0;
 * var PRESSURE_RANGE_END = 10;
 * var DEVICE_TEMPERATURE_RANGE_START = -40;
 * var DEVICE_TEMPERATURE_RANGE_END = 60; 
 */

/**
 * The starting value of the pressure range.
 * @type {number}
 */
var PRESSURE_RANGE_START = 0;

/**
 * The ending value of the pressure range.
 * @type {number}
 */
var PRESSURE_RANGE_END= 10;

/**
 * The starting value of the device temperature range.
 * @type {number}
 */
var DEVICE_TEMPERATURE_RANGE_START = -40;

/**
 * The ending value of the device temperature range.
 * @type {number}
 */
var DEVICE_TEMPERATURE_RANGE_END = 60;


/**
 * Decode uplink entry point
 * @typedef  {Object}    input          - An object provided by the IoT Flow framework
 * @property {number[]}  input.bytes    - Array of bytes represented as numbers as it has been sent from the device
 * @property {number}    input.fPort    - The Port Field on which the uplink has been sent
 * @property {Date}      input.recvTime - The uplink message time recorded by the LoRaWAN network server
 */

/**
 * Decoded uplink data
 * @typedef  {Object}               output     - An object to be returned to the IoT Flow framework
 * @property {Object.<string, *>}   data       - The open JavaScript object representing the decoded uplink payload when no errors occurred
 * @property {string[]}             [errors]   - A list of error messages while decoding the uplink payload
 * @property {string[]}             [warnings] - A list of warning messages that do not prevent the driver from decoding the uplink payload
 */

/**
 * @typedef {Object}  DecodedUplink  - An object that represents the decoded uplink payload
 */

/**
 * To decode the uplink data defined by LoRaWAN
 * @access public
 * @param   {input}     input - The object to decode
 * @returns {output}          - The decoded object
 */
function decodeUplink(input) {
    return decode(input);
}

/**
 * To decode from hex encoded string
 * @access public
 * @param   {number}    fPort             - The Port Field on which the uplink has been sent
 * @param   {string}    hexEncodedString  - A hex encoded string has been sent from the device
 * @returns {output}                      - The decoded object
 */
function decodeHexString(fPort, hexEncodedString) {
    /**
     * @type {input}
     */
    var input = {};
    input.bytes = convertHexStringToBytes(hexEncodedString);
    input.fPort = fPort;

    return decode(input);
}

/**
 * To decode from base64 string
 * @access public
 * @param   {number}    fPort                   - The Port Field on which the uplink has been sent
 * @param   {string}    base64EncodedString     - A base64 encoded string has been sent from the device
 * @returns {output}                            - The decoded object
 */
function decodeBase64String(fPort, base64EncodedString) {
    /**
     * @type {input}
     */
    var input = {};
    input.bytes = convertBase64StringToBytes(base64EncodedString);
    input.fPort = fPort;

    return decode(input);
}


// ***********************************************************************************
// Private Decoding Section
// ***********************************************************************************

/**
 * Generic Data Channel Values
 */
var DEVICE_NAME = "PGW23.100.11";

var GENERIC_DATA_CHANNEL_RANGE_START = 2500;
var GENERIC_DATA_CHANNEL_RANGE_END = 12500;
var ERROR_VALUE = 0xffff;

var CHANNEL_NAMES_DICTIONARY = ['pressure', 'device temperature', 'battery voltage'];
var ALARM_EVENT_NAMES_DICTIONARY = ['triggered', 'disappeared'];
var ALARM_CHANNEL_NAMES_DICTIONARY = ['pressure', 'device temperature'];
var PROCESS_ALARM_TYPE_NAMES_DICTIONARY = ['low threshold', 'high threshold', 'falling slope', 'rising slope', 'low threshold with delay', 'high threshold with delay'];
var TECHNICAL_ALARM_CAUSE_OF_FAILURE_NAMES_DICTIONARY = ['', 'general failure'];
var DEVICE_ALARM_CAUSE_OF_FAILURE_NAMES_DICTIONARY = ['', 'device dependent'];
var DEVICE_ALARM_TYPE_NAMES_DICTIONARY = ['low temperature alarm'];

/**
 * The padStart() method of String values pads this string with another string (multiple times, if needed) until the resulting string reaches the given length.
 * The function is reimplemented to support ES5.
 * @access private
 * @param   {number}     targetLength - The length of the returned string
 * @param   {string}     padString - The string to modify
 * @returns {string}          - The decoded object
 */
String.prototype.padStart = function (targetLength, padString) {

    var tempString = this.valueOf();

    for(var i = this.length; i < targetLength; ++i)
    {
        tempString = padString + tempString;
    }

    return tempString; 
 };

/**
 * To decode the uplink data
 * @access private
 * @param   {input}     input - The object to decode
 * @returns {output}          - The decoded object
 */
function decode(input) {
    // Define output object

    var output = createOutputObject();
    output = checkMeasurementRanges(output);
    if (output.errors) {
        return output;
    }

    /* Select subfunction to decode message */
    switch (input.bytes[0]) {
        /* unused */
        default:
        case 0x00:
        case 0x06: // configuration status message is not supported
        case 0x09: // extended device identification message is not supported
            // Error, not enough bytes            
            output = addErrorMessage(output, "Data message type " + input.bytes[0].toString(16).padStart(2, "0") + " not supported");
            break;

        /* Data message */
        case 0x01:
        case 0x02:
            /* Check if all bytes needed for decoding are there */
            if (input.bytes.length == 7) {
                // decode
                output = decodeDataMessage(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Data message 01/02 needs 7 bytes but got " + input.bytes.length);
            }
            break;

        /* Process alarm */
        case 0x03:
            /* Check if all bytes needed for decoding are there and all bytes for each alarm */
            if (input.bytes.length >= 5 && !((input.bytes.length - 2) % 3)) {
                // decode
                output = decodeProcessAlarm(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Process alarm 03 needs at least 5 bytes and got " + input.bytes.length + ". Also all bytes for each alarm needed");
            }
            break;

        /* Technical alarm */
        case 0x04:
            /* Check if all bytes needed for decoding are there and all bytes for each alarm */
            if (input.bytes.length >= 5 && !((input.bytes.length - 2) % 3)) {
                // decode
                output = decodeTechnicalAlarm(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Technical alarm 04 needs 5 bytes but got " + input.bytes.length + ". Also all bytes for each alarm needed");
            }
            break;

        /* Device alarm */
        case 0x05:
            /* Check if all bytes needed for decoding are there */
            if (input.bytes.length == 4) {
                // decode
                output = decodeDeviceAlarm(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Device alarm 05 needs 4 bytes but got " + input.bytes.length);
            }
            break;

        /* Device identification */
        case 0x07:
            /* Check if all bytes needed for decoding are there */
            if (input.bytes.length == 41) {
                // decode
                output = decodeDeviceIdentification(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Identification message 07 needs 41 bytes but got " + input.bytes.length);
            }
            break;

        /* Keep alive */
        case 0x08:
            /* Check if all bytes needed for decoding are there */
            if (input.bytes.length == 3) {
                // Decode
                output = decodeKeepAliveMessage(input);
            }
            else {
                // Error, not enough bytes
                output = addErrorMessage(output, "Keep alive message 08 needs 3 bytes but got " + input.bytes.length);
            }
            break;
    }

    return output;
}

/**
 * Decodes a data message 01, 02 into an object
 * @access private
 * @param {Object}      input           - An object provided by the IoT Flow framework
 * @param {number[]}    input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}      input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}        input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}             - The decoded object
 */
function decodeDataMessage(input) {
    // Output
    var output = createOutputObject();

    // data message type
    output.data.messageType = input.bytes[0];

    // current configuration id
    output.data.configurationId = input.bytes[1];

    output.data.measurement = {};
    output.data.measurement.channels = [];

    // pressure - channel 0
    var pressure = {};
    pressure.channelId = 0;
    pressure.channelName = CHANNEL_NAMES_DICTIONARY[pressure.channelId];
    pressure.value = getCalculatedPressure(input.bytes[3] << 8 | input.bytes[4]);
    output.data.measurement.channels.push(pressure);

    // temperature - channel 1
    var temperature = {};
    temperature.channelId = 1;
    temperature.channelName = CHANNEL_NAMES_DICTIONARY[temperature.channelId];
    temperature.value = getCalculatedTemperature(input.bytes[5] << 8 | input.bytes[6]);
    output.data.measurement.channels.push(temperature);

    // battery voltage - channel x
    var batteryVoltage = {};
    batteryVoltage.channelId = 2;
    batteryVoltage.channelName = CHANNEL_NAMES_DICTIONARY[batteryVoltage.channelId];
    // battery voltage in V as single not double
    batteryVoltage.value = (input.bytes[2] / 10);
    output.data.measurement.channels.push(batteryVoltage);
    
    return output;
}

/**
 * Decodes a process alarm 03 into an object
 * @access private
 * @param {Object}          input           - An object provided by the IoT Flow framework
 * @param {number[]}        input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}          input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}            input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}                        - The decoded object
 */
function decodeProcessAlarm(input) {

    var output = createOutputObject();
    output.data.processAlarms = [];

    // data message type
    output.data.messageType = input.bytes[0];

    // current configuration id
    output.data.configurationId = input.bytes[1];

    for (var byteIndex = 2, alarmCounter = 0; byteIndex < input.bytes.length; byteIndex += 3, alarmCounter++) {
        output.data.processAlarms[alarmCounter] = {};

        // Alarm event 0 = triggered, 1 = disappeared
        output.data.processAlarms[alarmCounter].event = (input.bytes[byteIndex] & 0x80) >> 7;
        output.data.processAlarms[alarmCounter].eventName = ALARM_EVENT_NAMES_DICTIONARY[output.data.processAlarms[alarmCounter].event];

        // Alarm channel 0 = pressure, 1 = device temperature
        output.data.processAlarms[alarmCounter].channelId = (input.bytes[byteIndex] & 0x78) >> 3;
        output.data.processAlarms[alarmCounter].channelName = ALARM_CHANNEL_NAMES_DICTIONARY[output.data.processAlarms[alarmCounter].channelId];

        // Alarm channel 0 = falling thresh, 1 = rising thresh, 2 = fal slope, 3 = rising slope, 4 = fall thresh delay, 5 = rise thresh delay
        output.data.processAlarms[alarmCounter].alarmType = (input.bytes[byteIndex] & 0x07);
        output.data.processAlarms[alarmCounter].alarmTypeName = PROCESS_ALARM_TYPE_NAMES_DICTIONARY[output.data.processAlarms[alarmCounter].alarmType];

        // Alarm value
        output.data.processAlarms[alarmCounter].value = getRealValueByChannelNumberAndAlarmType(output.data.processAlarms[alarmCounter].channelId, 
            output.data.processAlarms[alarmCounter].alarmType,
            input.bytes[byteIndex + 1] << 8 | input.bytes[byteIndex + 2]);
            
        if (output.data.processAlarms[alarmCounter].value == ERROR_VALUE) {
            output = addWarningMessage(output, "Invalid data for " + output.data.processAlarms[alarmCounter].channelName + "channel");
        }

    }

    return output;
}

/**
 * Decodes a technical alarm 04 into an object
 * @access private
 * @param {Object}          input           - An object provided by the IoT Flow framework
 * @param {number[]}        input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}          input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}            input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}                 - The decoded object
 */
function decodeTechnicalAlarm(input) {
    // Output
    var output = createOutputObject();
    output.data.technicalAlarms = [];

    // data message type
    output.data.messageType = input.bytes[0];

    // current configuration id
    output.data.configurationId = input.bytes[1];

    for (var byteIndex = 2, alarmCounter = 0; byteIndex < input.bytes.length; byteIndex += 3, alarmCounter++) {
        output.data.technicalAlarms[alarmCounter] = {};

        // Alarm event 0 = triggered, 1 = disappeared
        output.data.technicalAlarms[alarmCounter].event = (input.bytes[byteIndex] & 0x80) >> 7;
        output.data.technicalAlarms[alarmCounter].eventName = ALARM_EVENT_NAMES_DICTIONARY[output.data.technicalAlarms[alarmCounter].event];

        // Alarm channel 0 = pressure, 1 = device temperature
        output.data.technicalAlarms[alarmCounter].channelId = (input.bytes[byteIndex] & 0x78) >> 3;
        output.data.technicalAlarms[alarmCounter].channelName = ALARM_CHANNEL_NAMES_DICTIONARY[output.data.technicalAlarms[alarmCounter].channelId];

        // Alarm channel 1 = general failure
        output.data.technicalAlarms[alarmCounter].causeOfFailure = (input.bytes[byteIndex] & 0x07);
        output.data.technicalAlarms[alarmCounter].causeOfFailureName = TECHNICAL_ALARM_CAUSE_OF_FAILURE_NAMES_DICTIONARY[output.data.technicalAlarms[alarmCounter].causeOfFailure];

        // Alarm value
        output.data.technicalAlarms[alarmCounter].value = getRealValueByChannelNumberAndAlarmType(output.data.technicalAlarms[alarmCounter].channelId, 0,
            input.bytes[byteIndex + 1] << 8 | input.bytes[byteIndex + 2]);

        if (output.data.technicalAlarms[alarmCounter].value == ERROR_VALUE) {
            output = addWarningMessage(output, "Invalid data for " + output.data.technicalAlarms[alarmCounter].channelName + "channel");
        }
    }

    return output;
}

/**
 * Decodes a device alarm 05 into an object
 * @access private
 * @param {Object}              input           - An object provided by the IoT Flow framework
 * @param {number[]}            input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}              input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}                input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}                     - The decoded object
 */
function decodeDeviceAlarm(input) {
    // Output
    var output = createOutputObject();

    // data message type
    output.data.messageType = input.bytes[0];

    // current configuration id
    output.data.configurationId = input.bytes[1];

    // Create deviceAlarm
    output.data.deviceAlarm = {};

    // Alarm event 0 = triggered, 1 = disappeared
    output.data.deviceAlarm.event = (input.bytes[2] & 0x80) >> 7;
    output.data.deviceAlarm.eventName = ALARM_EVENT_NAMES_DICTIONARY[output.data.deviceAlarm.event];

    // Generic or device dependent 1 = device dependent    
    output.data.deviceAlarm.causeOfFailure = (input.bytes[2] & 0x60) >> 6;
    output.data.deviceAlarm.causeOfFailureName = DEVICE_ALARM_CAUSE_OF_FAILURE_NAMES_DICTIONARY[output.data.deviceAlarm.causeOfFailure];

    // Alarm type 0 = low temperature alarm
    output.data.deviceAlarm.alarmType = (input.bytes[2] & 0x1f);
    output.data.deviceAlarm.alarmTypeName = DEVICE_ALARM_TYPE_NAMES_DICTIONARY[output.data.deviceAlarm.alarmType];

    // The alarm value is an int8, but we have an int32, so we shift 24 bits to the left and 24 bits to the right 
    // and as a result we get an 8 bit integer in a 32 bit integer
    output.data.deviceAlarm.value = input.bytes[3] << 24 >> 24;

    return output;
}

/**
 * Decodes a keep alive message 08 into an object
 * @access private
 * @param {Object}              input           - An object provided by the IoT Flow framework
 * @param {number[]}            input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}              input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}                input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}                     - The decoded object
 */
function decodeKeepAliveMessage(input) {
    // Output
    var output = createOutputObject();

    // data message type
    output.data.messageType = input.bytes[0];

    // current configuration id
    output.data.configurationId = input.bytes[1];

    output.data.deviceStatistic = {};
    // Battery level event indicator
    output.data.deviceStatistic.batteryLevelNewEvent = (input.bytes[2] & 0x80) >> 7 ? true : false;

    // battery level in percent
    output.data.deviceStatistic.batteryLevelPercent = input.bytes[2] & 0x7f;

    return output;
}

/**
 * Decodes a device identification message 07 into an object
 * @access private
 * @param {Object}              input           - An object provided by the IoT Flow framework
 * @param {number[]}            input.bytes     - Array of bytes represented as numbers as it has been sent from the device
 * @param {number}              input.fPort     - The Port Field on which the uplink has been sent
 * @param {Date}                input.recvTime  - The uplink message time recorded by the LoRaWAN network server
 * @returns {output}                            - The decoded object
 */
function decodeDeviceIdentification(input) {
    // Output
    var output = createOutputObject();

    // Data message type    
    output.data.messageType = input.bytes[0];

    // Configuration id
    output.data.configurationId = input.bytes[1];

    output.data.deviceInformation = {};
    // Product id raw
    output.data.deviceInformation.productId = input.bytes[2];

    // Wireless module type
    output.data.deviceInformation.productIdName = input.bytes[2] == 10 ? "PGW23.100.11" : input.bytes[2];

    // Wireless module firmware version
    output.data.deviceInformation.wirelessModuleFirmwareVersion = ((input.bytes[3] >> 4) & 0x0f).toString() + "." + (input.bytes[3] & 0x0f).toString() + "." + (input.bytes[4]).toString();

    // Wireless module hardware version
    output.data.deviceInformation.wirelessModuleHardwareVersion = ((input.bytes[5] >> 4) & 0x0f).toString() + "." + (input.bytes[5] & 0x0f).toString() + "." + (input.bytes[6]).toString();

    // Sensor module firmware version
    output.data.deviceInformation.sensorModuleFirmwareVersion = ((input.bytes[7] >> 4) & 0x0f).toString() + "." + (input.bytes[7] & 0x0f).toString() + "." + (input.bytes[8]).toString();

    // Sensor module hardware version
    output.data.deviceInformation.sensorModuleHardwareVersion = ((input.bytes[9] >> 4) & 0x0f).toString() + "." + (input.bytes[9] & 0x0f).toString() + "." + (input.bytes[10]).toString();

    // Sensor serial number
    output.data.deviceInformation.serialNumber = "";
    for (var i = 11; i < 22; i++) {
        if (input.bytes[i] == 0) break;
        output.data.deviceInformation.serialNumber += String.fromCharCode(input.bytes[i]);
    }

    // Pressure type
    switch (input.bytes[22]) {
        case 1:
            output.data.deviceInformation.pressureType = "absolute";
            break;

        case 2:
            output.data.deviceInformation.pressureType = "relative";
            break;

        case 3:
            output.data.deviceInformation.pressureType = "differential";
            break;

        default:
            output.data.deviceInformation.pressureType = "unknown";
            break;
    }

    // Min range pressure
    output.data.deviceInformation.measurementRangeStartPressure = convertHexToFloatIEEE754(input.bytes[26].toString(16).padStart(2, "0") + input.bytes[25].toString(16).padStart(2, "0") + input.bytes[24].toString(16).padStart(2, "0") + input.bytes[23].toString(16).padStart(2, "0"));
    output.data.deviceInformation.measurementRangeStartPressure = Number(output.data.deviceInformation.measurementRangeStartPressure.toFixed(6));

    // Max range pressure
    output.data.deviceInformation.measurementRangeEndPressure = convertHexToFloatIEEE754(input.bytes[30].toString(16).padStart(2, "0") + input.bytes[29].toString(16).padStart(2, "0") + input.bytes[28].toString(16).padStart(2, "0") + input.bytes[27].toString(16).padStart(2, "0"));
    output.data.deviceInformation.measurementRangeEndPressure = Number(output.data.deviceInformation.measurementRangeEndPressure.toFixed(6));

    // Min range device temperature
    output.data.deviceInformation.measurementRangeStartDeviceTemperature = convertHexToFloatIEEE754(input.bytes[34].toString(16).padStart(2, "0") + input.bytes[33].toString(16).padStart(2, "0") + input.bytes[32].toString(16).padStart(2, "0") + input.bytes[31].toString(16).padStart(2, "0"));
    output.data.deviceInformation.measurementRangeStartDeviceTemperature = Number(output.data.deviceInformation.measurementRangeStartDeviceTemperature.toFixed(6));

    // Max range device temperature
    output.data.deviceInformation.measurementRangeEndDeviceTemperature = convertHexToFloatIEEE754(input.bytes[38].toString(16).padStart(2, "0") + input.bytes[37].toString(16).padStart(2, "0") + input.bytes[36].toString(16).padStart(2, "0") + input.bytes[35].toString(16).padStart(2, "0"));
    output.data.deviceInformation.measurementRangeEndDeviceTemperature = Number(output.data.deviceInformation.measurementRangeEndDeviceTemperature.toFixed(6));

    // Unit pressure
    output.data.deviceInformation.pressureUnit = input.bytes[39];
    output.data.deviceInformation.pressureUnitName = returnPhysicalUnitFromId(input.bytes[39]);

    // Unit pressure
    output.data.deviceInformation.deviceTemperatureUnit = input.bytes[40];
    output.data.deviceInformation.deviceTemperatureUnitName = returnPhysicalUnitFromId(input.bytes[40]);

    return output;
}
// ***********************************************************************************
//          Additional Functions Section
// ***********************************************************************************
/**
 * Converts a hex string number to float number follows the IEEE 754 standard and it's ES5 compatible
 * @access private
 * @param  {string} hexString   - Float as string "3.141"
 * @return {number}             - returns a float
 * @see https://gist.github.com/Jozo132/2c0fae763f5dc6635a6714bb741d152f 2022 by Jozo132 
 */
function convertHexToFloatIEEE754(hexString) {
    var int = parseInt(hexString, 16);
    if (int > 0 || int < 0) {
        var sign = (int >>> 31) ? -1 : 1;
        var exp = (int >>> 23 & 0xff) - 127;
        var mantissa = ((int & 0x7fffff) + 0x800000).toString(2);
        var float32 = 0
        for (var i = 0; i < mantissa.length; i += 1) { float32 += parseInt(mantissa[i]) ? Math.pow(2, exp) : 0; exp-- }
        return float32 * sign;
    } else return 0
}

/**
 * Checks the user defined measurement ranges
 * @access private
 * @param  {output} output - Output object
 * @return {output}        - Returns if the ranges are correct defined
 */
function checkMeasurementRanges(output) {
    if (typeof PRESSURE_RANGE_START === 'undefined') {
        output = addErrorMessage(output, "The PRESSURE_RANGE_START was not set.");
    }

    if (typeof PRESSURE_RANGE_END === 'undefined') {
        output = addErrorMessage(output, "The PRESSURE_RANGE_END was not set.");
    }

    if (typeof DEVICE_TEMPERATURE_RANGE_START === 'undefined') {
        output = addErrorMessage(output, "The DEVICE_TEMPERATURE_RANGE_START was not set.");
    }

    if (typeof DEVICE_TEMPERATURE_RANGE_END === 'undefined') {
        output = addErrorMessage(output, "The DEVICE_TEMPERATURE_RANGE_END was not set.");
    }

    if (PRESSURE_RANGE_START >= PRESSURE_RANGE_END) {
        output = addErrorMessage(output, "The PRESSURE_RANGE_START must not be greater or equal to PRESSURE_RANGE_END, " + PRESSURE_RANGE_START + " >= " + PRESSURE_RANGE_END + ". ");
    }

    if (DEVICE_TEMPERATURE_RANGE_START >= DEVICE_TEMPERATURE_RANGE_END) {
        output = addErrorMessage(output, "The DEVICE_TEMPERATURE_RANGE_START must not be greater or equal to DEVICE_TEMPERATURE_RANGE_END, " + DEVICE_TEMPERATURE_RANGE_START + " >= " + DEVICE_TEMPERATURE_RANGE_END + ".");
    }

    return output;
}

/**
 * Add warning to output object
 * @param {output} output
 * @param {string} warningMessage
 * @access private 
 */
function addWarningMessage(output, warningMessage) {
    // use only functional supported by ECMA-262 5th edition. The nullish assign and .at are not supported. output.warnings ??= [];
    output.warnings = output.warnings || [];
    output.warnings.push(DEVICE_NAME + " (JS): " + warningMessage);
    return output;
}

/**
 * Add private to output object
 * @param {output} output
 * @param {string} errorMessage
 * @access private 
 */
function addErrorMessage(output, errorMessage) {
    output.errors = output.errors || [];
    output.errors.push(DEVICE_NAME + " (JS): " + errorMessage);
    return output;
}

/**
 * Create an empty output object
 * @returns {output}        - Returns an output object
 * @access private
 */
function createOutputObject() {
    return {
        data: {},
    }
}


/**
 * Set measurement ranges only for test purposes
 * @access protected
 * @param  {Number} pressureRangeStart   range start
 * @param  {Number} pressureRangeEnd     range end
 * @param  {Number} temperatureRangeStart   range start
 * @param  {Number} temperatureRangeEnd     range end
 */
function setMeasurementRanges(pressureRangeStart, pressureRangeEnd, temperatureRangeStart, temperatureRangeEnd) {
    PRESSURE_RANGE_START = pressureRangeStart;
    PRESSURE_RANGE_END = pressureRangeEnd;
    DEVICE_TEMPERATURE_RANGE_START = temperatureRangeStart;
    DEVICE_TEMPERATURE_RANGE_END = temperatureRangeEnd;
}


/**
 * Returns the real physical value of the channel value based on measurement range
 * @access private
 * @param  {Number} channelValue    channel value as integer 
 * @param  {Number} measurementRangeStart   range start
 * @param  {Number} measurementRangeEnd     range end
 * @param  {Number} measuringRangeStart   range start (MRS)
 * @param  {Number} measuringRangeEnd     range end (MRE)
 * @return {Number} Returns real physical value e.g. 10 °C
 */
function getCalculatedValue(channelValue, measurementRangeStart, measurementRangeEnd, measuringRangeStart, measuringRangeEnd) {
    var calculatedValue = (channelValue - measuringRangeStart) * ((measurementRangeEnd - measurementRangeStart) / (measuringRangeEnd - measuringRangeStart)) + measurementRangeStart;
    // round to the third number after the comma
    return Math.round(calculatedValue * 1000) / 1000;
}

/**
 * Returns the real physical value of pressure based on measurement range
 * @param {Number} channelValue  
 * @access private
 */
function getCalculatedPressure(channelValue) {
    return getCalculatedValue(channelValue, PRESSURE_RANGE_START, PRESSURE_RANGE_END, GENERIC_DATA_CHANNEL_RANGE_START, GENERIC_DATA_CHANNEL_RANGE_END);
}

/**
 * Returns the real physical value of temperature based on measurement range
 * @param {Number} channelValue  
 * @access private
 */
function getCalculatedTemperature(channelValue) {
    return getCalculatedValue(channelValue, DEVICE_TEMPERATURE_RANGE_START, DEVICE_TEMPERATURE_RANGE_END, GENERIC_DATA_CHANNEL_RANGE_START, GENERIC_DATA_CHANNEL_RANGE_END);
}

/**
 * Returns the real physical value of the channel number based on measurement range
 * @param {Number} channelValue  
 * @param {Number} alarmType  
 * @param {Number} channelNumber  
 * @access private
 */
function getRealValueByChannelNumberAndAlarmType(channelNumber, alarmType, channelValue) {
    if (channelNumber == 0) // pressure channel
    {
        if (alarmType == 3 || alarmType == 4) {
            return getSlopeValue(channelValue, PRESSURE_RANGE_START, PRESSURE_RANGE_END);
        }
        else {
            return getThresholdValue(channelValue, PRESSURE_RANGE_START, PRESSURE_RANGE_END);
        }
    }
    else if (channelNumber == 1) // temperature channel
    {
        if (alarmType == 3 || alarmType == 4) {
            return getSlopeValue(channelValue, DEVICE_TEMPERATURE_RANGE_START, DEVICE_TEMPERATURE_RANGE_END);
        }
        else {
            return getThresholdValue(channelValue, DEVICE_TEMPERATURE_RANGE_START, DEVICE_TEMPERATURE_RANGE_END);
        }
    }

    return ERROR_VALUE;
}

/**
 * Returns the real threshold value of pressure based on measurement range (measurend)
 * @param {Number} channelValue  
 * @param  {Number} measurementRangeStart   range start
 * @param  {Number} measurementRangeEnd     range end
 * @access private
 */
function getThresholdValue(channelValue, measurementRangeStart, measurementRangeEnd) {
    return getCalculatedValue(channelValue, measurementRangeStart, measurementRangeEnd, 2500, 12500);
}

/**
 * Returns the real physical value of pressure based on measurement range (measurend/minute)
 * @param {Number} channelValue  
 * @param  {Number} measurementRangeStart   range start
 * @param  {Number} measurementRangeEnd     range end
 * @access private
 */
function getSlopeValue(channelValue, measurementRangeStart, measurementRangeEnd) {
    return getCalculatedValue(channelValue, measurementRangeStart, measurementRangeEnd, 0, 10000);
}

/**
 * To convert a hex encoded string to a integer array
 * @param {string} hexEncodedString
 * @access private
 */
function convertHexStringToBytes(hexEncodedString) {
    if (hexEncodedString.startsWith("0x")) {
        hexEncodedString = hexEncodedString.slice(2);
    }

    // remove spaces
    hexEncodedString = hexEncodedString.replace(/\s/g, '');

    var bytes = [];

    // convert byte to byte (2 characters are 1 byte)
    for (var i = 0; i < hexEncodedString.length; i += 2) {
        // extract 2 characters
        var hex = hexEncodedString.substr(i, 2);

        // convert hex pair to integer
        var intValue = parseInt(hex, 16);

        bytes.push(intValue);
    }

    return bytes;
}

/**
 * To convert a base64 encoded string to a integer array
 * @param {string} base64EncodedString
 * @access private
 */
function convertBase64StringToBytes(base64EncodedString) {
    var bytes = [];

    // convert base64 to string
    var decodedBytes = Buffer.from(base64EncodedString, 'base64')

    // convert byte to byte (2 characters are 1 byte)
    for (var i = 0; i < decodedBytes.length; i++) {
        // convert byte to integer
        var intValue = decodedBytes[i];

        bytes.push(intValue);
    }

    return bytes;
}

/**
 * Returns the printable name of a measurand for a LPP supporting devices e.g.: 1 = "Temperature"
 * @access private
 * @param  {Number} id    Identifier as integer 
 * @return {string}       Returns the printable name of a physical unit PGW23.100.11 e.g.: 1 = "mBar"
 */
function returnPhysicalUnitFromId(id) {
    switch (id) {
        case 1:
            return "inH2O";
        case 2:
            return "inHg";
        case 3:
            return "ftH2O";
        case 4:
            return "mmH2O";
        case 5:
            return "mmHg";
        case 6:
            return "psi";
        case 7:
            return "bar";
        case 8:
            return "mbar";
        case 9:
            return "g/cm²";
        case 10:
            return "kg/cm²";
        case 11:
            return "Pa";
        case 12:
            return "kPa";
        case 13:
            return "Torr";
        case 14:
            return "at";
        case 145:
            return "inH2O (60 °F)";
        case 170:
            return "cmH2O (4 °C)";
        case 171:
            return "mH2O (4 °C)";
        case 172:
            return "cmHg";
        case 173:
            return "lb/ft²";
        case 174:
            return "hPa";
        case 175:
            return "psia";
        case 176:
            return "kg/m²";
        case 177:
            return "ftH2O (4 °C)";
        case 178:
            return "ftH2O (60 °F)";
        case 179:
            return "mHg";
        case 180:
            return "Mpsi";
        case 237:
            return "MPa";
        case 238:
            return "inH2O (4 °C)";
        case 239:
            return "mmH2O (4 °C)";
        case 32:
            return "°C";
        case 33:
            return "°F";
        default:
            return "Unknown";
    }
}

// ***********************************************************************************
//          Export functions Section
// ***********************************************************************************
if (typeof exports !== 'undefined') {
    exports.decodeUplink = decodeUplink;
    exports.setMeasurementRanges = setMeasurementRanges;
    exports.decodeHexString = decodeHexString;
    exports.decodeBase64String = decodeBase64String;
}



function extractFromSource(ctx) {
    //This is the source message as json
    const sourceObject = ctx.getJsonObject();

    const input = sourceObject;
    for (var key in sourceObject) {
         console.log(`key: ${key}, value: ${sourceObject.get(key)}`);  
    }
    
    const decoded = decode(input);

    console.log("Output:" + JSON.stringify(decoded));
    const  measurements = decoded.data.measurement.channels;


    //Define a new Measurement Value for pressure by assigning from source
    const fragmentPressureSeries = {
        value: measurements[0].value,
        unit: 'bar'
    };

    const fragmentPressure = {
        p: fragmentPressureSeries
    }; 

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    const fragmentPressureSV = new SubstitutionValue(fragmentPressure, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'c8y_Pressure', fragmentPressureSV);

    const typeSV = new SubstitutionValue("c8y_PressureMeasurement", TYPE.TEXT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'type', typeSV);

    const source = {
        id: ctx.getC8YIdentifier()
    };
    const sourceFragmentSV = new SubstitutionValue(source, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'source', sourceFragmentSV);

    //Set time mapping time -> time
    const time = new Date().toISOString();
    const timeSV = new SubstitutionValue(time, TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'time', timeSV);

    return result;
}