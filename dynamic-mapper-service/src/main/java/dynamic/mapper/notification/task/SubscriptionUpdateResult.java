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

 package dynamic.mapper.notification.task;

import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.Future;

/**
 * Result object for subscription update tasks.
 * Contains information about successful and failed subscription operations.
 */
@Getter
public class SubscriptionUpdateResult {

    private final Map<String, Future<NotificationSubscriptionRepresentation>> addedSubscriptions;
    private final Set<String> removedSubscriptions;
    private final Map<String, String> failedOperations; // deviceId -> error message
    private final Exception error;

    private SubscriptionUpdateResult(Builder builder) {
        this.addedSubscriptions = Collections.unmodifiableMap(builder.addedSubscriptions);
        this.removedSubscriptions = Collections.unmodifiableSet(builder.removedSubscriptions);
        this.failedOperations = Collections.unmodifiableMap(builder.failedOperations);
        this.error = builder.error;
    }

    public int getAddedCount() {
        return addedSubscriptions.size();
    }

    public int getRemovedCount() {
        return removedSubscriptions.size();
    }

    public int getFailedCount() {
        return failedOperations.size();
    }

    public boolean hasError() {
        return error != null;
    }

    public boolean isEmpty() {
        return addedSubscriptions.isEmpty() && 
               removedSubscriptions.isEmpty() && 
               failedOperations.isEmpty();
    }

    public static SubscriptionUpdateResult empty() {
        return builder().build();
    }

    public static SubscriptionUpdateResult withError(Exception error) {
        return builder().withError(error).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Future<NotificationSubscriptionRepresentation>> addedSubscriptions = new HashMap<>();
        private final Set<String> removedSubscriptions = new HashSet<>();
        private final Map<String, String> failedOperations = new HashMap<>();
        private Exception error;

        public Builder addSubscription(String deviceId, Future<NotificationSubscriptionRepresentation> future) {
            addedSubscriptions.put(deviceId, future);
            return this;
        }

        public Builder addUnsubscription(String deviceId) {
            removedSubscriptions.add(deviceId);
            return this;
        }

        public Builder addFailed(String deviceId, String errorMessage) {
            failedOperations.put(deviceId, errorMessage);
            return this;
        }

        public Builder withError(Exception error) {
            this.error = error;
            return this;
        }

        public SubscriptionUpdateResult build() {
            return new SubscriptionUpdateResult(this);
        }
    }
}
