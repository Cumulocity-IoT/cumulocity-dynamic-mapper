/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.extension;


/** Utility for managing the extensions. */

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class ExtensionsComponent {

	@Autowired
	private InventoryApi inventory;

	/** Fragment name containing the extension details within the managed object for that extension */
	public static final String PROCESSOR_EXTENSION_TYPE = "d11r_processorExtension";
	public static final String PROCESSOR_EXTENSION_INTERNAL_NAME = "dynamic-mapping-extension-internal";

	/** Get all extensions. */
	public Iterable<ManagedObjectRepresentation> get() {
		ManagedObjectCollection mo = inventory.getManagedObjectsByFilter(new InventoryFilter().byFragmentType(PROCESSOR_EXTENSION_TYPE));
		return mo.get(2000).allPages();
	}

	public List<ManagedObjectRepresentation> getInternal() {
		return select(get(), ex -> ((Map)ex.get(PROCESSOR_EXTENSION_TYPE)).get("external").equals(false));
	}

	public List<ManagedObjectRepresentation> getExternal() {
		return select(get(), ex -> ((Map)ex.get(PROCESSOR_EXTENSION_TYPE)).get("external").equals(true));
	}

	static <T> List<T> select(Iterable<T> it, Predicate<T> pred) {
		return StreamSupport.stream(it.spliterator(), false)
			.filter(pred)
			.collect(Collectors.toList());
	}
}