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

package dynamic.mapper.core.facade;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.identity.ExternalIDCollection;
import com.cumulocity.sdk.client.identity.IdentityApi;

import dynamic.mapper.core.mock.MockIdentity;

import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class IdentityFacade {

    public static final int PAGE_SIZE = 100;

    @Autowired
    private MockIdentity identityMock;

    @Autowired
    private IdentityApi identityApi;

    public ExternalIDRepresentation create(ManagedObjectRepresentation mor, ID id, Boolean testing) {
        ExternalIDRepresentation externalIDRepresentation = new ExternalIDRepresentation();
        externalIDRepresentation.setType(id.getType());
        externalIDRepresentation.setExternalId(id.getValue());
        externalIDRepresentation.setManagedObject(mor);
        if (testing == null || !testing) {
            return identityApi.create(externalIDRepresentation);
        } else {
            return identityMock.create(externalIDRepresentation);
        }
    }

    public ExternalIDRepresentation resolveExternalId2GlobalId(ID externalID, Boolean testing, Semaphore c8ySemaphore) {
        if (!testing) {
            try {
                c8ySemaphore.acquire();
                return identityApi.getExternalId(externalID);
            } catch (InterruptedException e) {
                log.error("Failed to acquire semaphore for resolving external ID to global ID", e);
            } finally {
                c8ySemaphore.release();
            }
        } else {
            return identityMock.getExternalId(externalID);
        }
        return null;
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(GId gid, String externalIdType,
            Boolean testing, Semaphore c8ySemaphore) {
        if (!testing) {
            MutableObject<ExternalIDRepresentation> result = new MutableObject<ExternalIDRepresentation>(null);
            try {
                c8ySemaphore.acquire();
                ExternalIDCollection collection = identityApi.getExternalIdsOfGlobalId(gid);
                for (ExternalIDRepresentation externalId : collection.get(PAGE_SIZE).allPages()) {
                    if (externalId.getType().equals(externalIdType)) {
                        result.setValue(externalId);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.error("Failed to acquire semaphore for resolving external ID to global ID", e);
            } finally {
                c8ySemaphore.release();
            }
            return result.getValue();
        } else {
            return identityMock.getExternalIdsOfGlobalId(gid);
        }
    }

    public void clearMockIdentityCache() {
        identityMock.clear();
    }

}
