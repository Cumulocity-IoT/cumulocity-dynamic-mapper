---
title: Google Cloud Pub/Sub connector
---

### Google Cloud Pub/Sub connector {#pubsub-connector}

The **Google Cloud Pub/Sub** connector works in both directions. Outbound, it publishes Cumulocity data such as
measurements, alarms, and events to a Pub/Sub topic. Inbound, it reads from a Pub/Sub subscription and passes each message
to the inbound mappings.

The service account needs the role `roles/pubsub.publisher` for outbound and `roles/pubsub.subscriber` for inbound.

| Property | Default | Description |
|---|---|---|
| **Project ID** (required) | none | The Google Cloud project that owns the topic and the subscription. |
| **Topic ID** (required) | `input-messages` | The default topic to publish to. A publish topic set in a mapping overrides it for that mapping. |
| **Subscription ID** | none | The name of an existing subscription to read from. Required when inbound mappings exist. Create the subscription in advance and bind it to the inbound topic. |
| **Authentication mode** (required) | Service Account Key | Service Account Key, or Application Default Credentials (ADC). |
| **Service account key** | none | The full JSON key of the service account. Shown for the Service Account Key mode. It is a secret. |
| **ADC credentials JSON** | none | The content of the file that `gcloud auth application-default login` creates, usually `~/.config/gcloud/application_default_credentials.json`. Shown for the ADC mode. It is a secret. |
| **Publish timeout (s)** (required) | 30 | How long the connector waits for a publish acknowledgement before it treats the request as failed. |
