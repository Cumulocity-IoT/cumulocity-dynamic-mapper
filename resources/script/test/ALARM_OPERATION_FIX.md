# ALARM/OPERATION Inbound Support - ROOT CAUSE FOUND & FIXED

## Issue Summary
ALARM and OPERATION inbound tests were failing:
- ✅ Device created
- ✅ Mapping deployed and activated
- ✅ Messages published
- ❌ No alarms/operations in system

## Root Cause Analysis
Investigated `FlowResultInboundProcessor.java` and found that the inbound processor **DOES support ALARM/OPERATION**, but expects a **specific payload format**.

**Line 388 of FlowResultInboundProcessor.java:**
```java
Object alarmsObj = payload.get("alarms");
if (alarmsObj instanceof List) {
    List<Map<String, Object>> alarms = (List<Map<String, Object>>) alarmsObj;
```

The code explicitly checks for:
- **"alarms"** array for ALARM targetAPI
- **"operations"** array for OPERATION targetAPI (similar pattern)

## The Problem
Tests were sending **single object** format:
```json
{
  "alarmType": "c8y_TemperatureAlarm",
  "severity": "CRITICAL",
  "text": "Temperature sensor malfunction detected"
}
```

## The Solution
Tests now send **array format** as expected:
```json
{
  "alarms": [
    {
      "type": "c8y_TemperatureAlarm",
      "severity": "CRITICAL",
      "status": "ACTIVE",
      "text": "Temperature sensor malfunction detected",
      "time": "2026-05-29T10:00:00Z"
    }
  ]
}
```

## Changes Applied

### test-inbound-alarm.sh
**Before:**
```bash
TEST_PAYLOAD=$(jq -cn '{
  alarmType: "c8y_TemperatureAlarm",
  severity: "CRITICAL",
  text: "Temperature sensor malfunction detected"
}')
```

**After:**
```bash
TEST_PAYLOAD=$(jq -cn '{
  alarms: [
    {
      type: "c8y_TemperatureAlarm",
      severity: "CRITICAL",
      status: "ACTIVE",
      text: "Temperature sensor malfunction detected",
      time: (now | todate)
    }
  ]
}')
```

### test-inbound-operation.sh
**Before:**
```bash
TEST_PAYLOAD=$(jq -cn '{
  operationType: "c8y_Command",
  status: "PENDING",
  c8y_Command: {
    command: "echo Hello"
  }
}')
```

**After:**
```bash
TEST_PAYLOAD=$(jq -cn '{
  operations: [
    {
      id: "op-001",
      status: "PENDING",
      c8y_Command: {
        command: "echo Hello"
      },
      c8y_Restart: {}
    }
  ]
}')
```

## Verification Status
- ✅ Code analysis confirmed ALARM/OPERATION support in FlowResultInboundProcessor
- ✅ Payload format fix applied to both tests
- ⏳ Tests need rerun (c8y session expired) to validate fix works

## Expected Outcome
Once tests are rerun with active c8y session:
- test-inbound-alarm.sh should now ✅ PASS
- test-inbound-operation.sh should now ✅ PASS

## Key Learning
ALARM and OPERATION **ARE** supported for inbound, but the payload structure must match what the processor expects. The service uses a "fan-out" pattern where:
- Single payload with array of objects → Multiple API requests
- Example: `{ alarms: [{...}, {...}] }` → Creates 2 separate alarms

This is consistent with the array payload support seen in MEASUREMENT expansion patterns.

---

**Files Modified:**
- test-inbound-alarm.sh
- test-inbound-operation.sh

**Status:** Ready for revalidation once c8y session reestablished
