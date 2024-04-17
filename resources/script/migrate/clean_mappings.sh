###
# script to delete mappings
# this can be used after a migration to a new version of the dynamic mapper. in case the structure of the mappings has changed and become invalid
### 
c8y inventory list --type d11r_mapping | c8y inventory delete