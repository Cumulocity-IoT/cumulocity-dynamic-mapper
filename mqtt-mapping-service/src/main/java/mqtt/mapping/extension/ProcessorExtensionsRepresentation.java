package mqtt.mapping.extension;


/** Utility for managing the extensions. */
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

@Component
public class ProcessorExtensionsRepresentation {
	/** Get all extensions. */
	public Iterable<ManagedObjectRepresentation> get() {
		ManagedObjectCollection mo = inventory.getManagedObjectsByFilter(new InventoryFilter().byFragmentType(PROCESSOR_EXTENSION_TYPE));
		return mo.get(2000).allPages();
	}

	@Autowired
	private InventoryApi inventory;

	/** Fragment name containing the extension details within the managed object for that extension */
	public static final String PROCESSOR_EXTENSION_TYPE = "c8y_mqttMapping_Extension";
}