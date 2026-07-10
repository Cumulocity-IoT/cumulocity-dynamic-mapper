/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.model;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing notification subscription details and status")
public class NotificationSubscriptionResponse {

    @Schema(description = "Cumulocity IoT API type", example = "MEASUREMENT")
    private API api;

    @Schema(description = "Name of the subscription", example = "temperature-sensors")
    private String subscriptionName;

    @Schema(description = "List of subscribed devices")
    private List<Device> devices;

    @Schema(description = "List of subscribed device types")
    private List<String> types;

    @Schema(description = "Unique subscription identifier")
    private String subscriptionId;

    @Schema(description = "Current subscription status")
    private SubscriptionStatus status;

    @Schema(description = "Pagination metadata for the returned devices")
    private Paging paging;

    public enum SubscriptionStatus {
        ACTIVE, INACTIVE, ERROR, PENDING
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Page statistics for the device list")
    public static class Paging {

        @Schema(description = "Current page (1-based)", example = "1")
        private int currentPage;

        @Schema(description = "Number of items requested per page", example = "30")
        private int pageSize;

        @Schema(description = "Total number of pages; null unless withTotalPages was requested")
        private Integer totalPages;

        @Schema(description = "Total number of subscriptions; null unless withTotalPages was requested")
        private Long totalElements;

        @Schema(description = "True when another page can be loaded")
        private boolean hasNext;
    }
}