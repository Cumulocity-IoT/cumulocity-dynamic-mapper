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
package dynamic.mapper.processor.flow;

/**
 * Details of external ID (which will be looked up by IDP to get the C8Y id, and optionally used to create a device)
 */
public class ExternalSource {
    
    /** External ID to be looked up and/or created to get C8Y "id" */
    private String externalId;
    
    /** e.g. "c8y_Serial" */
    private String type;
    
    /** default true (false for advanced users, e.g. if they want to create somewhere deeper in the hierarchy) */
    private Boolean autoCreateDeviceMO;
    
    /** To support adding child assets/devices */
    private String parentId;
    
    /** If creating a child, what kind to create */
    private String childReference; // "device" | "asset" | "addition"
    
    /** Transport/MQTT client ID */
    private String clientId;
    
    // Constructors
    public ExternalSource() {}
    
    public ExternalSource(String externalId, String type) {
        this.externalId = externalId;
        this.type = type;
    }
    
    // Getters and Setters
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public Boolean getAutoCreateDeviceMO() { return autoCreateDeviceMO; }
    public void setAutoCreateDeviceMO(Boolean autoCreateDeviceMO) { this.autoCreateDeviceMO = autoCreateDeviceMO; }
    
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    
    public String getChildReference() { return childReference; }
    public void setChildReference(String childReference) { this.childReference = childReference; }
    
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
}