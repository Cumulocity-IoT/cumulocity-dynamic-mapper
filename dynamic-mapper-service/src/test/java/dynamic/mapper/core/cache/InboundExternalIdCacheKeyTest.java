/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.core.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * The cache key is type plus value. C8YAgent evicts a device after an HTTP 422 with an ID built from
 * the request's external ID type; building it from the value twice evicted nothing.
 */
class InboundExternalIdCacheKeyTest {

    @Test
    void evictionNeedsTheExternalIdTypeNotTheValueTwice() {
        InboundExternalIdCache cache = new InboundExternalIdCache(10, "t");
        cache.putIdForExternalId(new ID("c8y_Serial", "berlin_01"), new ExternalIDRepresentation());

        cache.removeIdForExternalId(new ID("berlin_01", "berlin_01"));
        assertNotNull(cache.getIdByExternalId(new ID("c8y_Serial", "berlin_01")), "wrong type evicts nothing");

        cache.removeIdForExternalId(new ID("c8y_Serial", "berlin_01"));
        assertNull(cache.getIdByExternalId(new ID("c8y_Serial", "berlin_01")));
    }
}
