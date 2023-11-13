#  Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
#  and/or its subsidiaries and/or its affiliates and/or their licensors.
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christof Strack, Stefan Witschel
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christoph Strack, Stefan Witschel

import base64
import requests
import json
import sys
import getopt


def main(argv):
    try:
        opts, args = getopt.getopt(
            argv, "U:u:p:f:d", ["password=", "url=", "user=", "file=", "dummy="])
    except:
        print('import_mappings.py -U <url> -u <user> -p <password> -f <file>')
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

    url = f"{url}/inventory/managedObjects"
    up = f'{user}:{secret}'
    up_encoded = base64.b64encode(up.encode("utf-8")).decode("utf-8")
    token = f'Basic {up_encoded}'

    headers = {
        'Authorization': token,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    mappings = []
    with open(file, 'r') as f:
        mappings = json.load(f)
    # Closing file
    f.close()
    
    print("Reading " + str(len(mappings)) + " from file: " + file)   
        
    for index, mapping in enumerate(mappings):
        # step 1: create new mapping
        managed_object_mapping = {
            "name": f"Mapping - {index + 1}",
            "type": "d11r_mapping",
            "d11r_mapping": mapping}
        managed_object_mapping_dump = json.dumps(managed_object_mapping)
        #print ("About to upload:" + payload_create_mo)
        response_post = requests.request("POST", url, headers=headers, data=managed_object_mapping_dump)

        # step 2: update mapping with id
        response_json = response_post.json()
        managed_object_mapping['d11r_mapping']['id'] = response_json['id']
        managed_object_mapping_dump = json.dumps(managed_object_mapping)
        url_put = f"{url}/{response_json['id']}"
        response_put = requests.request("PUT", url_put, headers=headers, data=managed_object_mapping_dump)
        print ("Created mapping:" + str(response_json['id']))


if __name__ == "__main__":
    main(sys.argv[1:])
