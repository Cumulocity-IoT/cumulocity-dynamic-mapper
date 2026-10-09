/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.configuration;

import dynamic.mapper.connector.core.client.ConnectorType;
import lombok.Data;

@Data
public class ConnectorId  {

	public ConnectorId(String name, String identifier) {
        this.name = name;
        this.identifier = identifier;
    }
    
    public ConnectorId(String name, String identifier, ConnectorType connectorType) {
        this.name = name;
        this.identifier = identifier;
        this.connectorType = connectorType;
    }
    
	public String name;

	public String identifier;

    public ConnectorType connectorType;

    /**
     * How the connector is named in messages meant for people: the connector's name, which is what
     * the connector list shows. The identifier is an internal key (hidden in the UI by default), so
     * it is only the fallback for a connector without a name.
     */
    public String displayName() {
        return name != null && !name.isBlank() ? name : identifier;
    }

    static public ConnectorId INTERNAL = new ConnectorId("INTERNAL", "INTERNAL");

}
