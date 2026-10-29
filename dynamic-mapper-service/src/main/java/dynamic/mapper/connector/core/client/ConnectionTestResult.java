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

package dynamic.mapper.connector.core.client;

/**
 * Outcome of {@link AConnectorClient#testConnection()}. {@code message} is safe to show in the UI
 * (never contains secrets); {@code statusCode} is the protocol status where one exists (HTTP), else null.
 */
public record ConnectionTestResult(boolean success, String message, Integer statusCode) {

    public static ConnectionTestResult ok(String message, Integer statusCode) {
        return new ConnectionTestResult(true, message, statusCode);
    }

    public static ConnectionTestResult failed(String message, Integer statusCode) {
        return new ConnectionTestResult(false, message, statusCode);
    }
}
