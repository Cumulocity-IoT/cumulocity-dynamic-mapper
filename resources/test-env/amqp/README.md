# AMQP Test Environment

Local AMQP (RabbitMQ) test environment using Docker Compose.

## Prerequisites

- Docker Desktop installed and running
- Docker Compose V2+ (included with Docker Desktop)

## Quick Start

### Start the AMQP Server

```bash
# Navigate to the AMQP test environment directory
cd resources/test-env/amqp

# Start RabbitMQ
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f rabbitmq
```

### Stop the AMQP Server

```bash
# Stop and remove containers
docker compose down

# Stop and remove containers + volumes (clean slate)
docker compose down -v
```

## Access Points

### AMQP Connection
- **Protocol**: `amqp://`
- **Host**: `localhost`
- **Port**: `5672`
- **Username**: `guest`
- **Password**: `guest`
- **Virtual Host**: `/`

### AMQPS Connection (SSL/TLS)
- **Protocol**: `amqps://`
- **Host**: `localhost`
- **Port**: `5671`
- **Username**: `guest`
- **Password**: `guest`

### Management UI
- **URL**: http://localhost:15672
- **Username**: `guest`
- **Password**: `guest`

The Management UI provides:
- Queue and exchange management
- Message publishing and consumption
- Connection and channel monitoring
- User and permission management
- Cluster status and metrics

## Testing with AMQPTestClient

### Update Environment Variables

```bash
# Source the main setup script
source ../../../setup-env.sh

# Override for local RabbitMQ
export AMQP_BROKER_HOST=localhost
export AMQP_BROKER_PORT=5672
export AMQP_PROTOCOL=amqp://
```

### Run Publisher Test

```bash
cd ../../../dynamic-mapper-service

# Compile
mvn clean compile

# Run publisher
mvn exec:java -Dexec.mainClass="dynamic.mapper.AMQPTestClient"
```

### Run Subscriber Test

```bash
# Run subscriber (keeps running until CTRL+C)
mvn exec:java -Dexec.mainClass="dynamic.mapper.AMQPTestClient" -Dexec.args="subscribe"
```

## Common Operations

### Create Exchange via Management UI

1. Open http://localhost:15672
2. Login with `guest` / `guest`
3. Go to **Exchanges** tab
4. Click **Add a new exchange**
5. Configure:
   - Name: `test-exchange`
   - Type: `topic`
   - Durability: `Durable`
6. Click **Add exchange**

### Create Queue via Management UI

1. Go to **Queues** tab
2. Click **Add a new queue**
3. Configure:
   - Name: `test-queue`
   - Durability: `Durable`
4. Click **Add queue**

### Bind Queue to Exchange

1. Click on your queue name (`test-queue`)
2. Scroll to **Bindings**
3. Under **Add binding from this queue**, configure:
   - From exchange: `test-exchange`
   - Routing key: `test.#` (matches test.* patterns)
4. Click **Bind**

### Publish Test Message via UI

1. Go to **Exchanges** tab
2. Click on `test-exchange`
3. Expand **Publish message**
4. Configure:
   - Routing key: `test.measurement`
   - Payload: `{"test": "message"}`
5. Click **Publish message**

### Monitor Messages

1. Go to **Queues** tab
2. Click on your queue name
3. Expand **Get messages**
4. Click **Get Message(s)**

## Using with Dynamic Mapper Connector

### Connector Configuration

Configure your AMQP connector in the Dynamic Mapper UI:

```json
{
  "protocol": "amqp://",
  "host": "localhost",
  "port": 5672,
  "virtualHost": "/",
  "username": "guest",
  "password": "guest",
  "exchange": "test-exchange",
  "exchangeType": "topic",
  "queuePrefix": "",
  "autoDeleteQueue": false,
  "automaticRecovery": true
}
```

### Inbound Mapping Example

- **Subscribe Topic**: `test.measurement`
- **Queue**: Will be created as `test.measurement` (or with prefix if configured)
- **Routing Key**: `test.measurement`

### Outbound Mapping Example

- **Publish Topic**: `test.measurement`
- **Routing Key**: `test.measurement`
- **Exchange**: `test-exchange`

## Troubleshooting

### Check RabbitMQ Logs

```bash
docker compose logs -f rabbitmq
```

### Check Container Status

```bash
docker compose ps
docker compose top
```

### Restart RabbitMQ

```bash
docker compose restart rabbitmq
```

### Clean Start (Reset All Data)

```bash
docker compose down -v
docker compose up -d
```

### Access RabbitMQ CLI

```bash
docker exec -it rabbitmq bash

# Inside container
rabbitmqctl status
rabbitmqctl list_queues
rabbitmqctl list_exchanges
rabbitmqctl list_bindings
rabbitmqctl list_connections
rabbitmqctl list_channels
```

### Check Connection from Host

```bash
# Check if port is accessible
nc -zv localhost 5672

# Check if management UI is accessible
curl -u guest:guest http://localhost:15672/api/overview
```

## Configuration Files

### Persistent Data

Data is stored in Docker volumes:
- `rabbitmq-data`: Message store and metadata
- `rabbitmq-logs`: Log files

To backup:
```bash
docker run --rm -v amqp_rabbitmq-data:/data -v $(pwd):/backup ubuntu tar czf /backup/rabbitmq-backup.tar.gz /data
```

To restore:
```bash
docker run --rm -v amqp_rabbitmq-data:/data -v $(pwd):/backup ubuntu tar xzf /backup/rabbitmq-backup.tar.gz -C /
```

## Resources

- [RabbitMQ Documentation](https://www.rabbitmq.com/documentation.html)
- [RabbitMQ Management Plugin](https://www.rabbitmq.com/management.html)
- [AMQP 0-9-1 Protocol](https://www.rabbitmq.com/protocol.html)
- [RabbitMQ Tutorials](https://www.rabbitmq.com/getstarted.html)

## Security Note

⚠️ **Default credentials are used for local development only.**

For production:
1. Change default credentials
2. Enable SSL/TLS (port 5671)
3. Configure proper user permissions
4. Use virtual hosts for isolation
5. Enable authentication plugins if needed
