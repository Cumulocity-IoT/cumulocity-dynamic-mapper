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

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString()
@AllArgsConstructor
public class ServiceConfiguration  implements Cloneable, InitializingBean{
    public static final String INBOUND_CODE_TEMPLATE = "INBOUND";
    public static final String OUTBOUND_CODE_TEMPLATE = "OUTBOUND";
    public static final String SHARED_CODE_TEMPLATE = "SHARED";

    @Value("${APP.template.code.inbound}")
    private String inboundCodeTemplate;

    @Value("${APP.template.code.outbound}")
    private String outboundCodeTemplate;

    @Value("${APP.template.code.shared}")
    private String sharedCodeTemplate;

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
        this.codeTemplates = new HashMap<>(); 
    }

    @Override
    public void afterPropertiesSet() {
        // Initialize code templates after properties are injected
        initCodeTemplates();
    }

    public void initCodeTemplates() {
        Map<String, String> codeTemplates = new HashMap<>();
        codeTemplates.put(INBOUND_CODE_TEMPLATE, inboundCodeTemplate);
        codeTemplates.put(OUTBOUND_CODE_TEMPLATE, outboundCodeTemplate);
        codeTemplates.put(SHARED_CODE_TEMPLATE, sharedCodeTemplate);
        this.codeTemplates = codeTemplates;
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
