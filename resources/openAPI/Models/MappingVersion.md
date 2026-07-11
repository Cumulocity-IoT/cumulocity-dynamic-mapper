# MappingVersion
## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
| **id** | **String** | Managed-object id of this version record, assigned by Cumulocity Core | [optional] [default to null] |
| **identifier** | **String** | Functional identifier of the owning mapping line | [default to null] |
| **version** | **String** | Semantic version (MAJOR.MINOR.PATCH), unique within the mapping line; null for the draft | [optional] [default to null] |
| **snapshot** | [**Mapping**](Mapping.md) | Immutable copy of the full mapping configuration for this version | [default to null] |
| **createdAt** | **Long** | Timestamp the version was published (epoch millis) | [optional] [default to null] |
| **createdBy** | **String** | User who published the version | [optional] [default to null] |
| **note** | **String** | Optional free-text change note | [optional] [default to null] |
| **draft** | **Boolean** |  | [optional] [default to null] |

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)

