package com.telecom.bccs.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BCCS Product Catalog API")
                .version("4.0.0")
                .description("Read-only catalog API for 3rd-party partners (cached, contract-first)")
                .contact(new Contact().name("BCCS Platform Team")));
    }
}
