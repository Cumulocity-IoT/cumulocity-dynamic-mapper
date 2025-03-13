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

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    public static final String INBOUND_CODE_TEMPLATE = "INBOUND";
    public static final String OUTBOUND_CODE_TEMPLATE = "OUTBOUND";
    public static final String SHARED_CODE_TEMPLATE = "SHARED";

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
        this.codeTemplates = initCodeTemplates();
    }

    public static Map<String, String> initCodeTemplates() {
        Map<String, String> codeTemplates = new HashMap<>();
        codeTemplates.put(INBOUND_CODE_TEMPLATE,
                "ZnVuY3Rpb24gZXh0cmFjdEZyb21Tb3VyY2UoY3R4KSB7CgogICAgLy9UaGlzIGlzIHRoZSBzb3VyY2UgbWVzc2FnZSBhcyBqc29uCiAgICBjb25zdCBzb3VyY2VPYmplY3QgPSBjdHguZ2V0SnNvbk9iamVjdCgpOwogICAgZm9yICh2YXIga2V5IGluIHNvdXJjZU9iamVjdCkgewogICAgICAgIGNvbnNvbGUubG9nKGBrZXk6ICR7a2V5fSwgdmFsdWU6ICR7c291cmNlT2JqZWN0LmdldChrZXkpfWApOyAgCiAgICB9CgogICAgLy9EZWZpbmUgYSBuZXcgTWVhc3VyZW1lbnQgVmFsdWUgZm9yIFRlbXBlcmF0dXJlcyBieSBhc3NpZ25pbmcgZnJvbSBzb3VyY2UKICAgIGNvbnN0IGZyYWdtZW50VGVtcGVyYXR1cmVTZXJpZXMgPSB7CiAgICAgICAgdmFsdWU6IHNvdXJjZU9iamVjdC5nZXQoJ3RlbXBlcmF0dXJlJyksCiAgICAgICAgdW5pdDogc291cmNlT2JqZWN0LmdldCgndW5pdCcpCiAgICB9OwoKICAgIC8vQXNzaWduIFZhbHVlcyB0byBTZXJpZXMKICAgIGNvbnN0IGZyYWdtZW50VGVtcGVyYXR1cmUgPSB7CiAgICAgICAgVDogZnJhZ21lbnRUZW1wZXJhdHVyZVNlcmllcwogICAgfTsKICAgCiAgICAvLyBTdWJzdGl0dXRpb246IFN0cmluZyBrZXksIE9iamVjdCB2YWx1ZSwgTWFwcGluZ1N1YnN0aXR1dGlvbi5TdWJzdGl0dXRlVmFsdWUuVFlQRSB0eXBlLCBSZXBhaXJTdHJhdGVneSByZXBhaXJTdHJhdGVneQogICAgLy9EZWZpbmUgdGltZSBtYXBwaW5nIHRpbWUgLT4gdGltZQogICAgY29uc3QgdGltZSA9IG5ldyBTdWJzdGl0dXRpb24oJ3RpbWUnLCBzb3VyY2VPYmplY3QuZ2V0KCd0aW1lJyksICdURVhUVUFMJywgJ0RFRkFVTFQnKTsKICAgIAogICAgLy9EZWZpbmUgdGVtcGVyYXR1cmUgZnJhZ21lbnQgbWFwcGluZyB0ZW1wZXJhdHVyZSAtPiBjOHlfVGVtcGVyYXR1cmUuVC52YWx1ZS91bml0CiAgICBjb25zdCB0ZW1wZXJhdHVyZSA9IG5ldyBTdWJzdGl0dXRpb24oJ2M4eV9UZW1wZXJhdHVyZU1lYXN1cmVtZW50JywgZnJhZ21lbnRUZW1wZXJhdHVyZSwgJ09CSkVDVCcsICdERUZBVUxUJyk7CgogICAgLy9EZWZpbmUgRGV2aWNlIElkZW50aWZpZXIKICAgIGNvbnN0IGRldmljZUlkZW50aWZpZXIgPSBuZXcgU3Vic3RpdHV0aW9uKGN0eC5nZXRHZW5lcmljRGV2aWNlSWRlbnRpZmllcigpLCBzb3VyY2VPYmplY3QuZ2V0KCdfVE9QSUNfTEVWRUxfJylbMV0sICdURVhUVUFMJywgJ0RFRkFVTFQnKTsKICAgIAogICAgLy9SZXR1cm4gdW5kZWZpbmVkIHRvIHNraXAgdGhlIGN1cnJlbnQgbWVzc2FnZSBmb3IgZnVydGhlciBwcm9jZXNzaW5nCiAgICAvL3JldHVybiB1bmRlZmluZWQ7CiAgICAKICAgIHJldHVybiBuZXcgU3Vic3RpdHV0aW9uUmVzdWx0KFtkZXZpY2VJZGVudGlmaWVyLCB0aW1lLCB0ZW1wZXJhdHVyZV0pOwp9");
        codeTemplates.put(OUTBOUND_CODE_TEMPLATE,
                "ZnVuY3Rpb24gZXh0cmFjdEZyb21Tb3VyY2UoY3R4KSB7CiAgICAvL1RoaXMgaXMgdGhlIHNvdXJjZSBtZXNzYWdlIGFzIGpzb24KICAgIGNvbnN0IHNvdXJjZU9iamVjdCA9IGN0eC5nZXRKc29uT2JqZWN0KCk7CgogICAgLy9Mb2cgYzh5IHNvdXJjZUlkCiAgICAvL2NvbnNvbGUubG9nKGBDOFkgc291cmNlSWQ6ICR7Y3R4LmdldEM4WUlkZW50aWZpZXIoKX1gKTsKICAgIC8vY29uc29sZS5sb2coYEM4WSBleHRlbmFsSWRlbnRpZmllcjogJHtjdHguZ2V0RXh0ZXJuYWxJZGVudGlmaWVyKCl9YCk7CgogICAgLy8gZm9yICh2YXIga2V5IGluIHNvdXJjZU9iamVjdCkgewogICAgLy8gICAgIGNvbnNvbGUubG9nKGBrZXk6ICR7a2V5fSwgdmFsdWU6ICR7c291cmNlT2JqZWN0LmdldChrZXkpfWApOyAgCiAgICAvLyB9CgogICAgLy9EZWZpbmUgYSBuZXcgTWVhc3VyZW1lbnQgVmFsdWUgZm9yIFRlbXBlcmF0dXJlcyBieSBhc3NpZ25pbmcgZnJvbSBzb3VyY2UKICAgIGNvbnN0IGZyYWdtZW50VGVtcGVyYXR1cmUgPSB7CiAgICAgICAgdmFsdWU6IHNvdXJjZU9iamVjdC5nZXQoJ2M4eV9UZW1wZXJhdHVyZU1lYXN1cmVtZW50JykuZ2V0KCdUJykuZ2V0KCd2YWx1ZScpLAogICAgICAgIHVuaXQ6IHNvdXJjZU9iamVjdC5nZXQoJ2M4eV9UZW1wZXJhdHVyZU1lYXN1cmVtZW50JykuZ2V0KCdUJykuZ2V0KCd1bml0JykKICAgIH07CgogICAgLy8gQ3JlYXRlIGEgbmV3IFN1YnN0aXR1dGlvblJlc3VsdCB3aXRoIHRoZSBIYXNoTWFwCiAgICBjb25zdCByZXN1bHQgPSBuZXcgU3Vic3RpdHV0aW9uUmVzdWx0KCk7CgogICAgLy8gU3Vic3RpdHV0aW9uOiBTdHJpbmcga2V5LCBPYmplY3QgdmFsdWUsIE1hcHBpbmdTdWJzdGl0dXRpb24uU3Vic3RpdHV0ZVZhbHVlLlRZUEUgdHlwZSwgUmVwYWlyU3RyYXRlZ3kgcmVwYWlyU3RyYXRlZ3kKICAgIC8vRGVmaW5lIHRpbWUgbWFwcGluZyB0aW1lIC0+IHRpbWUKICAgIGNvbnN0IHRpbWUgPSBuZXcgU3Vic3RpdHV0aW9uVmFsdWUoc291cmNlT2JqZWN0LmdldCgndGltZScpLCBUWVBFLlRFWFRVQUwsIFJlcGFpclN0cmF0ZWd5LkRFRkFVTFQsIGZhbHNlKTsKICAgIGFkZFRvU3Vic3RpdHV0aW9uc01hcChyZXN1bHQsICd0aW1lJywgdGltZSk7CgogICAgLy9EZWZpbmUgdGVtcGVyYXR1cmUgZnJhZ21lbnQgbWFwcGluZyB0ZW1wZXJhdHVyZSAtPiBjOHlfVGVtcGVyYXR1cmUuVC52YWx1ZS91bml0CiAgICBjb25zdCB0ZW1wZXJhdHVyZSA9IG5ldyBTdWJzdGl0dXRpb25WYWx1ZShmcmFnbWVudFRlbXBlcmF0dXJlLCBUWVBFLk9CSkVDVCwgUmVwYWlyU3RyYXRlZ3kuREVGQVVMVCwgZmFsc2UpOwogICAgYWRkVG9TdWJzdGl0dXRpb25zTWFwKHJlc3VsdCwgJ1RlbXBlcmF0dXJlJywgdGVtcGVyYXR1cmUpOwoKICAgIC8vRGVmaW5lIERldmljZSBJZGVudGlmaWVyCiAgICBjb25zdCBkZXZpY2VJZGVudGlmaWVyID0gbmV3IFN1YnN0aXR1dGlvblZhbHVlKGN0eC5nZXRFeHRlcm5hbElkZW50aWZpZXIoKSwgVFlQRS5URVhUVUFMLCBSZXBhaXJTdHJhdGVneS5ERUZBVUxULCBmYWxzZSk7CiAgICBhZGRUb1N1YnN0aXR1dGlvbnNNYXAocmVzdWx0LCAnX1RPUElDX0xFVkVMX1sxXScsIGRldmljZUlkZW50aWZpZXIpOwoKICAgIC8vVXNlIEM4WSBzb3VyY2VJZAogICAgY29uc3QgZGV2aWNlSWQgPSBuZXcgU3Vic3RpdHV0aW9uVmFsdWUoY3R4LmdldEM4WUlkZW50aWZpZXIoKSwgVFlQRS5URVhUVUFMLCBSZXBhaXJTdHJhdGVneS5ERUZBVUxULCBmYWxzZSk7CiAgICBhZGRUb1N1YnN0aXR1dGlvbnNNYXAocmVzdWx0LCAnZGV2aWNlSWQnLCBkZXZpY2VJZCk7CgogICAgcmV0dXJuIHJlc3VsdDsKfQ==");
        codeTemplates.put(SHARED_CODE_TEMPLATE,
                "Y29uc3QgU3Vic3RpdHV0aW9uUmVzdWx0ID0gSmF2YS50eXBlKCdkeW5hbWljLm1hcHBpbmcucHJvY2Vzc29yLm1vZGVsLlN1YnN0aXR1dGlvblJlc3VsdCcpOwpjb25zdCBTdWJzdGl0dXRpb25WYWx1ZSA9IEphdmEudHlwZSgnZHluYW1pYy5tYXBwaW5nLnByb2Nlc3Nvci5tb2RlbC5TdWJzdGl0dXRlVmFsdWUnKTsKY29uc3QgQXJyYXlMaXN0ID0gSmF2YS50eXBlKCdqYXZhLnV0aWwuQXJyYXlMaXN0Jyk7CmNvbnN0IEhhc2hNYXAgPSBKYXZhLnR5cGUoJ2phdmEudXRpbC5IYXNoTWFwJyk7CmNvbnN0IFRZUEUgPSBKYXZhLnR5cGUoJ2R5bmFtaWMubWFwcGluZy5wcm9jZXNzb3IubW9kZWwuU3Vic3RpdHV0ZVZhbHVlJFRZUEUnKTsKY29uc3QgUmVwYWlyU3RyYXRlZ3kgPSBKYXZhLnR5cGUoJ2R5bmFtaWMubWFwcGluZy5wcm9jZXNzb3IubW9kZWwuUmVwYWlyU3RyYXRlZ3knKTsKCi8vIEhlbHBlciBmdW5jdGlvbiB0byBhZGQgYSBTdWJzdGl0dXRpb25WYWx1ZSB0byB0aGUgbWFwCmZ1bmN0aW9uIGFkZFRvU3Vic3RpdHV0aW9uc01hcChyZXN1bHQsIGtleSwgdmFsdWUpIHsKICAgIGxldCBtYXAgPSByZXN1bHQuZ2V0U3Vic3RpdHV0aW9ucygpOwogICAgbGV0IHZhbHVlc0xpc3QgPSBtYXAuZ2V0KGtleSk7CgogICAgLy8gSWYgdGhlIGxpc3QgZG9lc24ndCBleGlzdCBmb3IgdGhpcyBrZXksIGNyZWF0ZSBpdAogICAgaWYgKHZhbHVlc0xpc3QgPT09IG51bGwgfHwgdmFsdWVzTGlzdCA9PT0gdW5kZWZpbmVkKSB7CiAgICAgICAgdmFsdWVzTGlzdCA9IG5ldyBBcnJheUxpc3QoKTsKICAgICAgICBtYXAucHV0KGtleSwgdmFsdWVzTGlzdCk7CiAgICB9CgogICAgLy8gQWRkIHRoZSB2YWx1ZSB0byB0aGUgbGlzdAogICAgdmFsdWVzTGlzdC5hZGQodmFsdWUpOwp9");
        return codeTemplates;
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
    public Map<String, String> codeTemplates;
}
