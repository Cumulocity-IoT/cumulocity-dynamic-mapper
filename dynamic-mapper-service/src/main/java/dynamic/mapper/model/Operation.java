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

package dynamic.mapper.model;

public enum Operation {
	ACTIVATE_MAPPING,
	CONNECT,
	DISCONNECT,
	REFRESH_STATUS_MAPPING,
	RELOAD_EXTENSIONS,
	RELOAD_MAPPINGS,
	RESET_STATISTICS_MAPPING,
	REFRESH_NOTIFICATIONS_SUBSCRIPTIONS,
	DEBUG_MAPPING,
	SNOOP_MAPPING,
	SNOOP_RESET,
	RESET_DEPLOYMENT_MAP,
	CLEAR_CACHE,
	APPLY_MAPPING_FILTER,
    COPY_SNOOPED_SOURCE_TEMPLATE,
    INIT_CODE_TEMPLATES,
    ADD_SAMPLE_MAPPINGS,
	CLEAR_CACHE_DEVICE_TO_CLIENT,

}
