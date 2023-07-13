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

    url = f"{url}/inventory/managedObjects?type=c8y_mqttMapping&pageSize=1000"
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
        #print ("Original:" + str(managedObject['c8y_mqttMapping']['id']) + "/" + str(managedObject['id']))
        mapping['c8y_mqttMapping']['id'] = mapping['id']
        mapping['c8y_mqttMapping']['name'] = 'Mapping - ' + str(index + 1)
        #print ("Changed:" + str(managedObject['c8y_mqttMapping']['id']))
        mappings.append(mapping['c8y_mqttMapping'])

    #print(json.dumps(mappings, indent=4))

    print("Writing " + str(len(mappings)) + " to file: " + file)

    with open(file, 'w') as f:
        f.write(json.dumps(mappings, indent=4))


if __name__ == "__main__":
    main(sys.argv[1:])
