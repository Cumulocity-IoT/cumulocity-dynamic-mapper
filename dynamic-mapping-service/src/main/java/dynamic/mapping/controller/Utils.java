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


import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class Utils  {
    private static final String ROLE_DYNAMIC_MAPPER_ADMIN = "ROLE_DYNAMIC_MAPPER_ADMIN";
    private static final String ROLE_DYNAMIC_MAPPER_CREATE = "ROLE_DYNAMIC_MAPPER_CREATE";

    static boolean userHasMappingAdminRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasUserRole = false;
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(ROLE_DYNAMIC_MAPPER_ADMIN))) {
            hasUserRole = true;
        }
        return hasUserRole;
    }

    static boolean userHasMappingCreateRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasUserRole = false;
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(ROLE_DYNAMIC_MAPPER_CREATE))) {
            hasUserRole = true;
        }
        return  userHasMappingAdminRole() || hasUserRole;
    }
}
