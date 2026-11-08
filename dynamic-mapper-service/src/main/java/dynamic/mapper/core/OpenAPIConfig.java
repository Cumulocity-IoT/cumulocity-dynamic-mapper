/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.core;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Value("${application.version}")
    private String applicationVersion;

    @Bean
    public OpenAPI myOpenAPI() {
        Contact contact = new Contact();
        contact.setName("Christof Strack, Stefan Witschel");
        contact.setUrl("https://www.cumulocity.com");

        License mitLicense = new License()
                .name(" Apache License")
                .url("https://raw.githubusercontent.com/Cumulocity-IoT/cumulocity-dynamic-mapper/refs/heads/main/LICENSE");

        Info info = new Info()
                .title("Cumulocity Dynamic Mapper")
                .version(applicationVersion)
                .contact(contact)
                .description("This API exposes endpoints to manage resources for the Cumulocity Dynamic Mapper.")
                .termsOfService(
                        "https://raw.githubusercontent.com/Cumulocity-IoT/cumulocity-dynamic-mapper/refs/heads/main/LICENSE")
                .license(mitLicense);

        Server server = new Server()
                .url("/service/dynamic-mapper-service")
                .description("Cumulocity Dynamic Mapper Service");

        return new OpenAPI()
                .info(info)
                .externalDocs(new ExternalDocumentation()
                        .description("Additional Documentation")
                        .url("https://cumulocity.com/docs/"))
                .servers(List.of(server));
    }
}
