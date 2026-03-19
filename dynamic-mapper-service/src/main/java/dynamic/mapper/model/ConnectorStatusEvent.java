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

package dynamic.mapper.model;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Status event representing the current connection state of a connector")
public class ConnectorStatusEvent implements Serializable {
	@NotNull
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name of the connector", example = "MQTT Broker")
	public String connectorName;

	@NotNull
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unique identifier of the connector", example = "mqtt-broker-01")
	public String connectorIdentifier;

	@NotNull
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Current connection status", implementation = ConnectorStatus.class, example = "CONNECTED")
	public ConnectorStatus status;

	@NotNull
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Status message or error description", example = "Connected successfully")
	public String message;

	@NotNull
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Timestamp of the status event in yyyy-MM-dd HH:mm:ss format", example = "2024-01-15 10:30:00")
	public String date;

	public ConnectorStatusEvent() {
		this.status = ConnectorStatus.UNKNOWN;
	}

	public ConnectorStatusEvent(ConnectorStatus status) {
		this.status = status;
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		Date now = new Date();
		this.date = dateFormat.format(now);
		this.message = "";
	}

	public static ConnectorStatusEvent unknown(String name, String identifier) {
		var res = new ConnectorStatusEvent(ConnectorStatus.UNKNOWN);
		res.connectorName = name;
		res.connectorIdentifier = identifier;

		return res;
	}

	public void updateStatus(ConnectorStatus st, boolean clearMessage) {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		Date now = new Date();
		date = dateFormat.format(now);
		status = st;
		if (clearMessage)
			message = "";
	}
}
