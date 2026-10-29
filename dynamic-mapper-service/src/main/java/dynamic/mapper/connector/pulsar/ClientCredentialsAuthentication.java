/*
 * Copyright (c) 2025 Cumulocity GmbH.
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
 */

package dynamic.mapper.connector.pulsar;

import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Pulsar authentication plugin for brokers that expect the auth method name {@code client_creds}
 * with {@code base64(clientId:clientSecret)} as command data.
 */
public class ClientCredentialsAuthentication implements Authentication {
    private static final long serialVersionUID = 1L;

    public static final String AUTH_NAME = "client_creds";

    private final String clientId;
    private final String clientSecret;

    public ClientCredentialsAuthentication(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String getAuthMethodName() {
        return AUTH_NAME;
    }

    @Override
    public AuthenticationDataProvider getAuthData() {
        return new AuthenticationDataProvider() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean hasDataFromCommand() {
                return true;
            }

            @Override
            public String getCommandData() {
                String authData = clientId + ":" + clientSecret;
                return Base64.getEncoder().encodeToString(authData.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    @Override
    public void configure(Map<String, String> authParams) {
        // credentials are passed via the constructor
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
}
