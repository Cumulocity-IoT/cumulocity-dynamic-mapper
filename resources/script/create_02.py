import requests
import json

url = "https://ck2.eu-latest.cumulocity.com/inventory/managedObjects?type=c8y_mqttMapping&pageSize=1000"

payload = json.dumps({
  "mqttHost": "daehpresal59615.hycloud.softwareag.com",
  "mqttPort": 8883,
  "user": "test",
  "password": "test123#",
  "clientId": "mqtt-mapper-agent",
  "useTLS": True
})
headers = {
  'Authorization': 'Basic dDMwNjgxNzM3OC9jaHJpc3RvZi5zdHJhY2tAc29mdHdhcmVhZy5jb206I01hbmFnZTI1MCFERkM=',
  'Content-Type': 'application/json',
  'Accept': 'application/json'
}

response = requests.request("GET", url, headers=headers, data=payload)
jsonResponse = response.json()

for managedObject in jsonResponse['managedObjects']:
    print ("original:" + managedObject['c8y_mqttMapping']['id'] + "/" + managedObject['id'])
    managedObject['c8y_mqttMapping']['id'] = managedObject['id']
    print ("changed:" + managedObject['c8y_mqttMapping']['id'])

#print(response.text)
