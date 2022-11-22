import base64
import requests
import json
import sys
import getopt


def main(argv):
    opts, args = getopt.getopt(
        argv, "U:u:p:f:d", ["password=", "url=", "user=", "file=", "dummy="])
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
