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
    sampleMappings = []

    for managedObject in jsonResponse['managedObjects']:
        #print ("Original:" + str(managedObject['c8y_mqttMapping']['id']) + "/" + str(managedObject['id']))
        managedObject['c8y_mqttMapping']['id'] = managedObject['id']
        #print ("Changed:" + str(managedObject['c8y_mqttMapping']['id']))
        sampleMappings.append(managedObject['c8y_mqttMapping'])

    #print(json.dumps(sampleMappings, indent=4))

    #sampleFilename = 'resources/script/sampleMapping/sampleMappings_01.json'

    print("Writing " + str(len(sampleMappings)) + " to file: " + file)

    with open(file, 'w') as f:
        f.write(json.dumps(sampleMappings, indent=4))


if __name__ == "__main__":
    main(sys.argv[1:])
