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

package dynamic.mapper.core;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

/**
 * Asks {@code NotificationSubscriber} to start or stop the Notification 2.0 subscription that keeps
 * the inventory cache of a managed object current. {@link CacheManager} and
 * {@link InventoryCacheEnrichmentService} publish this event instead of depending on
 * {@code NotificationSubscriber}, which depends on beans that in turn need {@code C8YAgent}.
 *
 * @param mor the managed object; {@code null} for {@link Action#UNSUBSCRIBE_ALL}
 */
public record InventoryCacheSubscriptionRequest(Action action, String tenant, ManagedObjectRepresentation mor) {

    public enum Action {
        SUBSCRIBE, UNSUBSCRIBE, UNSUBSCRIBE_ALL
    }
}
