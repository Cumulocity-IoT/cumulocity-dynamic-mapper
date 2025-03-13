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

package dynamic.mapping.configuration;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
	public ServiceConfiguration() {
		this.logPayload = false;
		this.logSubstitution = false;
		this.logConnectorErrorInBackend = false;
		this.sendConnectorLifecycle = false;
		this.sendMappingStatus = true;
		this.sendSubscriptionEvents = false;
		this.sendNotificationLifecycle = false;
		this.externalExtensionEnabled = true;
		this.outboundMappingEnabled = true;
		this.inboundExternalIdCacheSize = 0;
		this.inboundExternalIdCacheRetention = 1;
		this.sharedCode = "Y29uc3QgU3Vic3RpdHV0aW9uUmVzdWx0ID0gSmF2YS50eXBlKCdkeW5hbWljLm1hcHBpbmcucHJvY2Vzc29yLm1vZGVsLlN1YnN0aXR1dGlvblJlc3VsdCcpOwpjb25zdCBTdWJzdGl0dXRpb25WYWx1ZSA9IEphdmEudHlwZSgnZHluYW1pYy5tYXBwaW5nLnByb2Nlc3Nvci5tb2RlbC5TdWJzdGl0dXRlVmFsdWUnKTsKY29uc3QgQXJyYXlMaXN0ID0gSmF2YS50eXBlKCdqYXZhLnV0aWwuQXJyYXlMaXN0Jyk7CmNvbnN0IEhhc2hNYXAgPSBKYXZhLnR5cGUoJ2phdmEudXRpbC5IYXNoTWFwJyk7CmNvbnN0IFRZUEUgPSBKYXZhLnR5cGUoJ2R5bmFtaWMubWFwcGluZy5wcm9jZXNzb3IubW9kZWwuU3Vic3RpdHV0ZVZhbHVlJFRZUEUnKTsKY29uc3QgUmVwYWlyU3RyYXRlZ3kgPSBKYXZhLnR5cGUoJ2R5bmFtaWMubWFwcGluZy5wcm9jZXNzb3IubW9kZWwuUmVwYWlyU3RyYXRlZ3knKTsKCi8vIEhlbHBlciBmdW5jdGlvbiB0byBhZGQgYSBTdWJzdGl0dXRpb25WYWx1ZSB0byB0aGUgbWFwCmZ1bmN0aW9uIGFkZFRvU3Vic3RpdHV0aW9uc01hcChyZXN1bHQsIGtleSwgdmFsdWUpIHsKICAgIGxldCBtYXAgPSByZXN1bHQuZ2V0U3Vic3RpdHV0aW9ucygpOwogICAgbGV0IHZhbHVlc0xpc3QgPSBtYXAuZ2V0KGtleSk7CgogICAgLy8gSWYgdGhlIGxpc3QgZG9lc24ndCBleGlzdCBmb3IgdGhpcyBrZXksIGNyZWF0ZSBpdAogICAgaWYgKHZhbHVlc0xpc3QgPT09IG51bGwgfHwgdmFsdWVzTGlzdCA9PT0gdW5kZWZpbmVkKSB7CiAgICAgICAgdmFsdWVzTGlzdCA9IG5ldyBBcnJheUxpc3QoKTsKICAgICAgICBtYXAucHV0KGtleSwgdmFsdWVzTGlzdCk7CiAgICB9CgogICAgLy8gQWRkIHRoZSB2YWx1ZSB0byB0aGUgbGlzdAogICAgdmFsdWVzTGlzdC5hZGQodmFsdWUpOwp9";
    }

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean logPayload;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean logSubstitution;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean logConnectorErrorInBackend;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean sendConnectorLifecycle;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean sendMappingStatus;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean sendSubscriptionEvents;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean sendNotificationLifecycle;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean externalExtensionEnabled;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean outboundMappingEnabled;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public Integer inboundExternalIdCacheSize;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public Integer inboundExternalIdCacheRetention;

	@JsonSetter(nulls = Nulls.SKIP)
	public String sharedCode;
}
