import paho.mqtt.client as mqtt
import json
import time
import threading
import ssl
import random

# MQTT Configuration
broker = "broker.emqx.io"
port = 8883
topic = "loadTestJSONata/berlin_01"
# topic = "loadTestGraals/berlin_01"
client_id_prefix = "python-mqtt-sender-"

# Message configuration
starting_temp = 50
message_count = 1000
thread_count = 5

# Callback functions
def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print(f"Client {client._client_id.decode()} connected successfully")
    else:
        print(f"Client {client._client_id.decode()} failed to connect, return code {rc}")

def on_publish(client, userdata, mid, properties=None):
    print(f"Client {client._client_id.decode()} message ID: {mid} published")

def send_messages(thread_id):
    client_id = client_id_prefix + str(thread_id)
    # Specify callback_api_version=mqtt.CallbackAPIVersion.VERSION1 for compatibility
    client = mqtt.Client(client_id=client_id, callback_api_version=mqtt.CallbackAPIVersion.VERSION1)
    
    # Set up TLS for secure connection
    client.tls_set(cert_reqs=ssl.CERT_REQUIRED, tls_version=ssl.PROTOCOL_TLS)
    
    # Set callbacks
    client.on_connect = on_connect
    client.on_publish = on_publish
    
    try:
        # Connect to broker
        client.connect(broker, port, 60)
        client.loop_start()
        
        # Calculate how many messages this thread will send
        messages_per_thread = message_count // thread_count
        
        # Calculate the starting temperature for this thread
        thread_temp_offset = thread_id * messages_per_thread
        
        for i in range(messages_per_thread):
            temperature = starting_temp + thread_temp_offset + i
            
            payload = {
                "temperature": temperature,
                "oil": 40,  # Fixed value as per requirement
                "unit": "C",
                "externalId": "berlin_01"
            }
            
            # Convert dict to JSON
            message = json.dumps(payload)
            
            # Publish message
            result = client.publish(topic, message, qos=1)
            
            # Check if publish was successful
            if result[0] == 0:
                print(f"Thread {thread_id} sent: {message}")
            else:
                print(f"Thread {thread_id} failed to send message")
            
            # Add a small delay between messages
            time.sleep(0.5 + random.random())
        
        # Disconnect client
        client.loop_stop()
        client.disconnect()
        print(f"Thread {thread_id} completed sending {messages_per_thread} messages")
    except Exception as e:
        print(f"Error in thread {thread_id}: {e}")

def main():
    threads = []
    
    # Create and start threads
    for i in range(thread_count):
        thread = threading.Thread(target=send_messages, args=(i,))
        threads.append(thread)
        thread.start()
    
    # Wait for all threads to complete
    for thread in threads:
        thread.join()
    
    print("All messages sent successfully")

if __name__ == "__main__":
    main()