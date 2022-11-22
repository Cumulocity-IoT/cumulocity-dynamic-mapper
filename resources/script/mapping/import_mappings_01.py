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

    url = f"{url}/inventory/managedObjects"
    up = f'{user}:{secret}'
    up_encoded = base64.b64encode(up.encode("utf-8")).decode("utf-8")
    token = f'Basic {up_encoded}'

    headers = {
        'Authorization': token,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    sampleMappings = []
    with open(file, 'r') as f:
        sampleMappings = json.load(f)
    # Closing file
    f.close()
    
    print("Reading " + str(len(sampleMappings)) + " from file: " + file)   
        
    for index, mapping in enumerate(sampleMappings):
        managed_object_mapping = {
            "name": f"MQTT Mapping - {index}",
            "type": "c8y_mqttMapping_dummy",
            "c8y_mqttMapping": mapping}
        payload_create_mo = json.dumps(managed_object_mapping)
        #print ("About to upload:" + payload_create_mo)
        response = requests.request("POST", url, headers=headers, data=payload_create_mo)
        response_json = response.json()
        print ("Created mapping:" + str(response_json['id']))

if __name__ == "__main__":
    main(sys.argv[1:])
