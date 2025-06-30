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

package dynamic.mapper.core.cache;

import jakarta.validation.constraints.NotNull;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class CacheKey {
	@NotNull
	public String externalIdType;
	@NotNull
	public String externalId;

	// @Override
	// public boolean equals(Object o) {
	// if (this == o)
	// return true;
	// if (o == null || getClass() != o.getClass())
	// return false;
	// CacheKey cacheKey = (CacheKey) o;
	// return Objects.equals(externalIdType, cacheKey.externalIdType) &&
	// Objects.equals(externalId, cacheKey.externalId);
	// }

	// @Override
	// public int hashCode() {
	// return Objects.hash(externalIdType, externalId);
	// }
}
