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

package dynamic.mapper.util;

import java.security.SecureRandom;
import java.util.stream.Collectors;

public class Utils  {
    public static final String OPTION_CATEGORY_CONFIGURATION = "dynMappingService";
    public static final String MAPPER_PROCESSING_ALARM = "d11r_mapperProcessingAlarm";

	public static String createCustomUuid() {
	    return Utils.SECURE_RANDOM.ints(Utils.UUID_LENGTH, 0, 36) 
	            .mapToObj(i -> Character.toString(i < 10 ? '0' + i : 'a' + i - 10))
	            .collect(Collectors.joining());
	}

	public static final int UUID_LENGTH = 8;
	public static final SecureRandom SECURE_RANDOM = new SecureRandom();
}
