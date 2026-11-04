# ServiceLogPage
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **dateFrom** | **Date** | Start time of the first chunk in this page | [optional] [default to null] |
| **dateTo** | **Date** | End time of the last chunk in this page | [optional] [default to null] |
| **before** | **Date** | Cursor for the page before this one: the end time of this page&#39;s first chunk | [optional] [default to null] |
| **chunks** | **Integer** | Number of chunks in this page | [optional] [default to null] |
| **lines** | **Integer** | Number of log lines in this page | [optional] [default to null] |
| **omitted** | **Integer** | Number of lines left out of this page | [optional] [default to null] |
| **more** | **Boolean** | Whether more chunks exist beyond this page in the requested direction | [optional] [default to null] |
| **empty** | **Boolean** | True when no collected log exists at all for this tenant, as opposed to an empty page | [optional] [default to null] |
| **logs** | **String** | The log lines, one per line | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

