###
# script migrates the mappings form a format used in releases up to 4.6.1 to the new format used from 4.7.x onwards
# changes are:
# 1. rename 'source' -> 'sourceTemplate'
# 2. rename 'target' -> 'targetTemplate'
# 3. rename 'filterOutbound' -> 'filterMapping' only for outbound mappings
# 4. remove 'subscriptionTopic', instead the conent of 'mappingTopic' is used for managing the subscriptions
# 5. rename 'extension.event' -> 'extension.eventName'
# 6. rename 'extension.name' -> 'extension.extensionName'
# 6. add 'extension.extensionType: 'PROCESSOR_EXTENSION_SOURCE'
# 6. remove 'extension.loaded'
# this can be used after a migration to a new version of the dynamic mapper. in case the structure of the mappings has changed and become invalid
### 


import base64
import requests
import json
import sys
import getopt

def migrate_mapping(mapping):
    """Migrate a single mapping to the new format."""
    # Create a copy of the mapping to avoid modifying the original
    new_mapping = mapping.copy()

    # 1. rename 'source' -> 'sourceTemplate'
    if 'source' in new_mapping:
        new_mapping['sourceTemplate'] = new_mapping.pop('source')

    # 2. rename 'target' -> 'targetTemplate'
    if 'target' in new_mapping:
        new_mapping['targetTemplate'] = new_mapping.pop('target')

    # 3. rename 'filterOutbound' -> 'filterMapping' for outbound mappings
    if 'filterOutbound' in new_mapping and new_mapping.get('direction') == 'OUTBOUND':
        new_mapping['filterMapping'] = new_mapping.pop('filterOutbound')

    # 4. remove 'subscriptionTopic' (no need to explicitly remove as we're creating a new dict)
    if 'subscriptionTopic' in new_mapping:
        new_mapping.pop('subscriptionTopic')

    # 5 & 6. Update extension structure
    if 'extension' in new_mapping:
        new_extension = {}
        old_extension = new_mapping['extension']
        
        # rename 'event' -> 'eventName'
        if 'event' in old_extension:
            new_extension['eventName'] = old_extension['event']
        
        # rename 'name' -> 'extensionName'
        if 'name' in old_extension:
            new_extension['extensionName'] = old_extension['name']
        
        # add extensionType
        new_extension['extensionType'] = 'PROCESSOR_EXTENSION_SOURCE'
        
        # remove 'loaded'
        # (no need to explicitly remove as we're creating a new dict)
        
        new_mapping['extension'] = new_extension

    return new_mapping

def main(argv):
    try:
        opts, args = getopt.getopt(
            argv, "U:u:p:f:d", ["password=", "url=", "user=", "file=", "dummy="])
    except:
        print('export_mappings.py -U <url> -u <user> -p <password> -f <file>')
        sys.exit()

    for opt, arg in opts:
        if opt == '-h':
            print('create.py -U <url> -u <user> -p <password> -f <file>')
            sys.exit()
        elif opt in ("-U", "--url"):
            url = arg
        elif opt in ("-p", "--password"):
            secret = arg
        elif opt in ("-u", "--user"):
            user = arg
        elif opt in ("-f", "--file"):
            file = arg

    url = f"{url}/inventory/managedObjects?type=d11r_mapping&pageSize=1000"
    up = f'{user}:{secret}'
    up_encoded = base64.b64encode(up.encode("utf-8")).decode("utf-8")
    token = f'Basic {up_encoded}'

    headers = {
        'Authorization': token,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    response = requests.request("GET", url, headers=headers)
    jsonResponse = response.json()
    mappings = []

    for index, mapping in enumerate(jsonResponse['managedObjects']):
        mapping['d11r_mapping']['id'] = mapping['id']
        mapping['d11r_mapping']['name'] = 'Mapping - ' + str(index + 1)
        # Migrate the mapping to the new format
        migrated_mapping = migrate_mapping(mapping['d11r_mapping'])
        mappings.append(migrated_mapping)
    #print(json.dumps(mappings, indent=4))

    print("Writing " + str(len(mappings)) + " to file: " + file)

    with open(file, 'w') as f:
        f.write(json.dumps(mappings, indent=4))


if __name__ == "__main__":
    main(sys.argv[1:])