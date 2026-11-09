# ServiceLogsConfiguration
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **enabled** | **Boolean** | Store this tenant&#39;s log lines so its administrators can read them under Monitoring &gt; Service logs. | [optional] [default to null] |
| **minLevel** | **String** | Lowest level that is stored: TRACE, DEBUG, INFO, WARN or ERROR. INFO lines can contain device payloads. | [optional] [default to null] |
| **intervalMs** | **Integer** | How often this tenant&#39;s collected lines are stored, in milliseconds (5000 to 3600000). | [optional] [default to null] |
| **maxLinesPerRun** | **Integer** | Most lines stored per run; the rest follows in the next run (1 to 5000). | [optional] [default to null] |
| **maxChunkBytes** | **Integer** | Size of one stored event in bytes (1024 to 262144). | [optional] [default to null] |
| **maxRecordBytes** | **Integer** | A single very long message or stack trace is cut to this size in bytes (256 to 65536, at most the event size). | [optional] [default to null] |
| **maxBufferedRecords** | **Integer** | Lines that may wait in memory between two runs; when full the oldest are dropped (100 to 10000, and at most 16 MB in total). | [optional] [default to null] |
| **retentionHours** | **Integer** | How long stored lines are kept, in hours (1 to 720). | [optional] [default to null] |
| **retentionEnabled** | **Boolean** | Delete stored lines once they are older than the retention. Keep on, or they pile up. | [optional] [default to null] |
| **retentionIntervalMs** | **Integer** | How often old lines are deleted, in milliseconds (60000 to 86400000). | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

