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

package dynamic.mapping.core;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Data
public class ConnectorStatusEvent implements Serializable {
	@NotNull
	public String connectorName;

	@NotNull
	public String connectorIdentifier;

	@NotNull
	public ConnectorStatus status;

	@NotNull
	public String message;

	@NotNull
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
