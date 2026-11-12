/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor;

/**
 * The outbound connector could not publish because its broker connection is down.
 *
 * <p>This is a server-side condition (HTTP 503): the message itself is fine, so the notification must not be
 * acknowledged and Cumulocity redelivers it. Unlike a failure caused by the message, it does not count
 * toward the poison-pill limit, because it says nothing about the message.</p>
 */
public class ConnectorNotConnectedException extends ProcessingException {
    public ConnectorNotConnectedException(String connectorName) {
        super("Cannot publish: connector " + connectorName + " is not connected", 503);
    }
}
