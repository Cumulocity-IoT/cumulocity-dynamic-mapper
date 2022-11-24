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

package mqtt.mapping.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.identity.IdentityApi;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.mock.MockIdentity;
import mqtt.mapping.processor.model.ProcessingContext;

@Slf4j
@Service
public class IdentityFacade {

    @Autowired
    private MockIdentity identityMock;

    @Autowired
    private IdentityApi identityApi;

    public ExternalIDRepresentation create(ManagedObjectRepresentation mor, ID id, ProcessingContext<?> context) {
        ExternalIDRepresentation externalIDRepresentation = new ExternalIDRepresentation();
        externalIDRepresentation.setType(id.getType());
        externalIDRepresentation.setExternalId(id.getValue());
        externalIDRepresentation.setManagedObject(mor);
        if (context == null || context.isSendPayload()) {
            return identityApi.create(externalIDRepresentation);
        } else {
            return identityMock.create(externalIDRepresentation);
        }
    }

    public ExternalIDRepresentation getExternalId(ID externalID, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return identityApi.getExternalId(externalID);
        } else {
            return identityMock.getExternalId(externalID);
        }
    }

}
