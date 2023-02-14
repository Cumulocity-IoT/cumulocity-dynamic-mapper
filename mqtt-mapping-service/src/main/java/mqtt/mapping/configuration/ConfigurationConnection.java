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

package mqtt.mapping.configuration;

import lombok.Data;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import javax.validation.constraints.NotNull;

@Data
@ToString ()
public class ConfigurationConnection implements Cloneable {

    @NotNull
    public String mqttHost;
    
    @NotNull
    public int mqttPort;

    @NotNull
    public String user;

    @NotNull
    @ToString.Exclude
    public String password;

    @NotNull
    public String clientId;

    @NotNull
    public boolean useTLS;

    @NotNull
    public boolean enabled;

    @NotNull
    public boolean useSelfSignedCertificate;

    public String fingerprintSelfSignedCertificate;

    @NotNull
    public String nameCertificate;

    public Object clone() 
    {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    public static boolean isValid (ConfigurationConnection mc){
        return (mc != null) && !StringUtils.isEmpty(mc.mqttHost) &&
        !(mc.mqttPort == 0) &&
      //  !StringUtils.isEmpty(mc.user) &&
      //  !StringUtils.isEmpty(mc.password) &&
        !StringUtils.isEmpty(mc.clientId);
    }

    public static boolean isEnabled(ConfigurationConnection mc) {
        return ConfigurationConnection.isValid(mc) && mc.enabled;
    }
}

