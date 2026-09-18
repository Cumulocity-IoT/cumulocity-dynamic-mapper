---
title: Overview
---

### What is the Dynamic Mapper? {#overview}

The Cumulocity Dynamic Mapper connects almost any message broker to Cumulocity IoT and converts the payloads your
devices already send into the Cumulocity domain model — without changing device firmware and without writing a
custom agent.

You describe the conversion once, in a graphical editor. From then on every message arriving on that topic is
transformed automatically.

There are only two things to create, and everything else in this documentation builds on them:

- A **connector** is the link to your broker — MQTT, Kafka, HTTP, AMQP, Pulsar, Google Pub/Sub or the Cumulocity
  MQTT Service.
- A **mapping** is the rule that turns one message into one or more Cumulocity objects — a measurement, event,
  alarm or managed object.

Mappings work in both directions. An **inbound** mapping takes a broker message and creates Cumulocity data. An
**outbound** mapping watches Cumulocity for changes and sends a message back to the broker.

Your tenant right now:
