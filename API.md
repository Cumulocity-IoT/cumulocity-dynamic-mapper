# API Documentation

The mapping microservice provides endpoints to control the lifecycle and manage mappings. in details these endpoint are:
1. ```.../configuration/connection```: retrieve and change the connection details to the MQTT broker
2. ```.../configuration/serice```: retrieve and change the configuration details, e.g. loglevel of the mapping service
3. ```.../operation```: execute operation: reload mappings, connect to broker, disconnect from broker, reset the monitoring statistic, reload extensions
4. ```.../monitoring/status/connector```: retrieve service status: is microservice connected to broker, are connection details loaded
5. ```.../monitoring/status/mapping```: retrieve mapping status: number of messages, errors processed per mapping
6. ```.../monitoring/tree```: all mappings are organised in a tree for efficient processing and resolving the mappings at runtime. This tree can be retrieved for debugging purposes.
7. ```.../monitoring/subscriptions```: retrieve all active subscriptions.
8. ```.../mapping```: retrieve, create, delete, update mappings
9. ```.../test/{method}?topic=URL_ENCODED_TOPIC```: this endpoint allows testing of a payload. The send parameter (boolean)  indicates if the transformed payload should be sent to Cumulocity after processing. The call return a list of ```ProcessingContext``` to record which mapping processed the payload and the outcome of the mapping process as well as error
10. ```.../extension/```: endpoint to retrieve a list of all extensions
11. ```.../extension/{extension-name}```: endpoint to retrieve/delete a specific extension