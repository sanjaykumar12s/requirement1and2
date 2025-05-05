package com.mycart.service.config;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

@Component
public class CamelRestConfig extends RouteBuilder {
    @Override
    public void configure() {
        restConfiguration()
                .component("netty-http")
                .host("0.0.0.0")
                .port(8080)
                .bindingMode(RestBindingMode.json)
                .dataFormatProperty("prettyPrint", "true");
    }
}
