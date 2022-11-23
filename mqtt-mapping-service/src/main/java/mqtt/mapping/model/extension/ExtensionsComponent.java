package mqtt.mapping.model.extension;


/** Utility for managing the extensions. */
import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

@Component
public class ExtensionsComponent {
	/** Get all extensions. */
	public Iterable<ManagedObjectRepresentation> get() {
		ManagedObjectCollection mo = inventory.getManagedObjectsByFilter(new InventoryFilter().byFragmentType(PROCESSOR_EXTENSION_TYPE));
		return mo.get(2000).allPages();
	}

	public Iterable<ManagedObjectRepresentation> getInternal() {
		return select(get(), ex -> ((Map)ex.get(PROCESSOR_EXTENSION_TYPE)).get("external").equals(false));
	}

	public Iterable<ManagedObjectRepresentation> getExternal() {
		return select(get(), ex -> ((Map)ex.get(PROCESSOR_EXTENSION_TYPE)).get("external").equals(true));

	}

	static <T> Iterable<T> select(Iterable<T> it, Predicate<T> pred) {
		return StreamSupport.stream(it.spliterator(), false)
			.filter(pred)
			.collect(Collectors.toList());
	}
	@Autowired
	private InventoryApi inventory;

	/** Fragment name containing the extension details within the managed object for that extension */
	public static final String PROCESSOR_EXTENSION_TYPE = "c8y_mqttMapping_Extension";
	public static final String PROCESSOR_EXTENSION_INTERNAL_NAME = "mqtt-mapping-extension-internal";
}