// local currentTime = std.native('timestamp');
// local now = std.extVar('current_time'); 
local now = _.Now();

{
  "type": "c8y_Uplink",
  "bytes": [
      1,
      0,
      35,
      9,
      185,
      35,
      110
    ],
  "time": now,
  "text": "`New uplink event: " + now,
}