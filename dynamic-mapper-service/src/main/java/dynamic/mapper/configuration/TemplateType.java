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

package dynamic.mapper.configuration;

import dynamic.mapper.model.Direction;

/**
 * Identity of a code template: what it is used for and, implicitly, which way the data flows.
 *
 * <p>The direction is a property of the type, not something a template declares for itself.
 * Templates used to carry a separate {@code @direction} annotation, which could disagree with
 * {@code @templateType} and was never read by anything; {@link #getDirection()} is now the single
 * source of truth. {@code SHARED} and {@code SYSTEM} apply to both directions and report
 * {@code null} rather than {@link Direction#UNSPECIFIED}, matching the value tenants already have
 * stored for them.
 */
public enum TemplateType {
    /** @deprecated No longer in use. */
    @Deprecated(since = "6.3", forRemoval = false)
    INBOUND(Direction.INBOUND),
    /** @deprecated No longer in use. */
    @Deprecated(since = "6.3", forRemoval = false)
    OUTBOUND(Direction.OUTBOUND),
    /** @deprecated Substitution As Code is no longer supported. Kept for deserialization of existing tenant data. */
    @Deprecated(since = "6.3", forRemoval = true)
    INBOUND_SUBSTITUTION_AS_CODE(Direction.INBOUND),
    /** @deprecated Substitution As Code is no longer supported. Kept for deserialization of existing tenant data. */
    @Deprecated(since = "6.3", forRemoval = true)
    OUTBOUND_SUBSTITUTION_AS_CODE(Direction.OUTBOUND),
    INBOUND_SMART_FUNCTION(Direction.INBOUND),
    OUTBOUND_SMART_FUNCTION(Direction.OUTBOUND),
    SHARED(null),
    SYSTEM(null),
    ;

    private final Direction direction;

    TemplateType(Direction direction) {
        this.direction = direction;
    }

    /**
     * The direction this template type applies to, or {@code null} for the direction-agnostic
     * types ({@code SHARED}, {@code SYSTEM}).
     */
    public Direction getDirection() {
        return direction;
    }
}
