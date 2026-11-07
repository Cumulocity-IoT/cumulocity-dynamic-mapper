package dynamic.mapper.core;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.configuration.ServiceConfiguration;

/**
 * Everything a {@link TenantScoped} bean may need to create its per-tenant state. Built once per initialization
 * attempt by {@link BootstrapService} from platform data, before any bean runs, so beans do no platform I/O.
 *
 * @param tenant           tenant identifier
 * @param credentials      the tenant's service-user credentials
 * @param serviceConfiguration the normalized service configuration (cache sizes and code templates set)
 * @param mapperServiceMo  managed object of the mapper service agent
 * @param deviceToClientMo managed object of the device-to-client map; {@code null} when device isolation is off
 */
public record TenantInitContext(String tenant, MicroserviceCredentials credentials,
        ServiceConfiguration serviceConfiguration, ManagedObjectRepresentation mapperServiceMo,
        ManagedObjectRepresentation deviceToClientMo) {
}
