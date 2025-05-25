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

package dynamic.mapping.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapping.model.ThreadInfo;

@RestController
@RequestMapping("/actuator/connectors")
public class ConnectorMonitoringController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingComponent mappingComponent;

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @GetMapping("/active")
    public Map<String, Object> getActiveConnections() {
        String tenant = contextService.getContext().getTenant();

        Map<String, ThreadInfo> activeConnections = AConnectorClient.getActiveConnections(tenant);
        Map<String, Object> result = new HashMap<>();
        result.put("activeConnectionCount", activeConnections.size());
        result.put("activeConnections", activeConnections.values());
        result.put("timestamp", System.currentTimeMillis());

        // Group by connector type
        Map<String, Long> byType = activeConnections.values().stream()
                .collect(Collectors.groupingBy(ThreadInfo::getConnectorType, Collectors.counting()));
        result.put("connectionsByType", byType);

        // Group by thread type
        Map<String, Long> byThreadType = activeConnections.values().stream()
                .collect(Collectors.groupingBy(
                        info -> info.isVirtual() ? "virtual" : "platform",
                        Collectors.counting()));
        result.put("connectionsByThreadType", byThreadType);

        return result;
    }

    @GetMapping("/long-running")
    public Map<String, Object> getLongRunningConnections(@RequestParam(defaultValue = "60000") long thresholdMs) {
        String tenant = contextService.getContext().getTenant();

        List<ThreadInfo> longRunning = AConnectorClient.getLongRunningConnections(tenant, thresholdMs);

        Map<String, Object> result = new HashMap<>();
        result.put("longRunningCount", longRunning.size());
        result.put("longRunningConnections", longRunning);
        result.put("thresholdMs", thresholdMs);

        return result;
    }

    @GetMapping("/virtual-threads")
    public Map<String, Object> getVirtualThreadConnections() {
        String tenant = contextService.getContext().getTenant();
        List<ThreadInfo> virtualThreads = AConnectorClient.getVirtualThreadConnections(tenant);

        Map<String, Object> result = new HashMap<>();
        result.put("virtualThreadCount", virtualThreads.size());
        result.put("virtualThreadConnections", virtualThreads);

        // Status distribution
        Map<String, Long> statusDistribution = virtualThreads.stream()
                .collect(Collectors.groupingBy(ThreadInfo::getStatus, Collectors.counting()));
        result.put("statusDistribution", statusDistribution);

        return result;
    }

    @PostMapping("/log-status")
    public String logActiveConnections() {
        String tenant = contextService.getContext().getTenant();
        AConnectorClient.logActiveConnections(tenant);
        return "Active connections logged";
    }
}
