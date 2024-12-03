#!/bin/sh
set -e

###
# script to delete connection configurations from the tenantoptions
# this can be used after a migration to a new version of the dynamic mapper. in case the structure of the connection configurations has changed and become invalid
### 
c8y tenantoptions getForCategory --category dynamic.mapper.service |  jq 'keys| .[] | select(startswith("credentials.connection.configuration"))' -r | c8y tenantoptions delete --category dynamic.mapper.service --key -.key