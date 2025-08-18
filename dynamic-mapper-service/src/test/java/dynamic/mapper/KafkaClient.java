/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper;

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaClient {
	KafkaProducer<String, String> testClient;
	static String brokerHost = System.getenv("kafka_broker_host");
	static String brokerUsername = System.getenv("BROKER_USERNAME");
	static String brokerPassword = System.getenv("BROKER_PASSWORD");
	static String groupId = System.getenv("group_id");
	static String topic = System.getenv("topic");
	static String saslMechanism = System.getenv("sasl_mechanism");

	public KafkaClient(KafkaProducer<String, String> sampleClient) {
		testClient = sampleClient;
	}

	public static void main(String[] args) {

		String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
		String jaasCfg = String.format(jaasTemplate, brokerUsername, brokerPassword);
		System.out.println("JAASConfig: " + jaasCfg);
		String serializer = StringSerializer.class.getName();

		Properties props = new Properties();
		props.put("key.serializer", serializer);
		props.put("value.serializer", serializer);
		props.put("security.protocol", "SASL_SSL");
		props.put("sasl.mechanism", "SCRAM-SHA-512");
		// props.put("sasl.mechanism", sasl_mechanism);
		// props.put("linger.ms", 1);
		// props.put("enable.idempotence", false);
		props.put("bootstrap.servers", brokerHost);
		props.put("group.id", groupId);
		props.put("sasl.jaas.config", jaasCfg);

		KafkaClient client = new KafkaClient(new KafkaProducer<>(props));
		client.testSendMeasurement();

	}

	private void testSendMeasurement() {

		String topic = KafkaClient.topic;
		System.out.println("Connecting to Kafka broker: " + brokerHost + "!");

		System.out.println("Publishing message on topic: " + topic);

		String payload = "{ \"deviceId\": \"863859042393327\", \"version\": \"1\",\"deviceType\": \"20\", \"deviceTimestamp\": \"1665473038000\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }";
		String key = "863859042393327";

		testClient.send(new ProducerRecord<String, String>(topic, key, payload));
		testClient.close();

		System.out.println("Message published");
		System.out.println("Disconnected");

	}

}
