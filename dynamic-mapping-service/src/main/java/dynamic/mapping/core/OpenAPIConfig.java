package dynamic.mapping.core;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI myOpenAPI() {

        Contact contact = new Contact();
        contact.setName("Stefan Witschel, Christof Strack");
        contact.setUrl("https://www.cumulocity.com");

        License mitLicense = new License()
            .name(" Apache License")
            .url("https://raw.githubusercontent.com/Cumulocity-IoT/cumulocity-dynamic-mapper/refs/heads/main/LICENSE");

        Info info = new Info()
            .title("Cumulocity Dynamic Mapper")
            .version("5.0.0")
            .contact(contact)
            .description("This API exposes endpoints to manage resources for the Cumulocity Dynamic Mapper.")
            .termsOfService("https://raw.githubusercontent.com/Cumulocity-IoT/cumulocity-dynamic-mapper/refs/heads/main/LICENSE")
            .license(mitLicense);

        return new OpenAPI()
            .info(info)
            .externalDocs(new ExternalDocumentation()
                .description("Additional Documentation")
                .url("https://cumulocity.com/docs/"));
    }
}
