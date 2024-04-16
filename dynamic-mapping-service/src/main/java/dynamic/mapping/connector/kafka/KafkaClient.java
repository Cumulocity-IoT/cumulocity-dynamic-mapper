/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.connector.kafka;

// import org.apache.kafka.clients.consumer.KafkaConsumer;
// import org.apache.kafka.clients.producer.KafkaProducer;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;


@Slf4j

// Use pattern to start/stop polling thread from Stackoverflow
// https://stackoverflow.com/questions/66103052/how-do-i-stop-a-previous-thread-that-is-listening-to-kafka-topic
public class KafkaClient extends AConnectorClient {

    // private KafkaConsumer<String, String> kafkaConsumer;
    // private KafkaProducer<String, String> kafkaProducer;
    @Override
    public boolean initialize() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'initialize'");
    }

    @Override
    public ConnectorSpecification getSpecification() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSpecification'");
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return false;
    }

    @Override
    public ConnectorSpecification getSpec() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSpec'");
    }

    @Override
    public void connect() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'connect'");
    }

    @Override
    public boolean isConnected() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isConnected'");
    }

    @Override
    public void disconnect() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'disconnect'");
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'close'");
    }

    @Override
    public String getConnectorIdent() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getConnectorIdent'");
    }

    @Override
    public String getConnectorName() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getConnectorName'");
    }

    @Override
    public void subscribe(String topic, QOS qos) throws ConnectorException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'subscribe'");
    }

    @Override
    public void unsubscribe(String topic) throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'unsubscribe'");
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isConfigValid'");
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'publishMEAO'");
    }
}
