package com.mycart.service.camelrouter;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class InventoryEnqueueRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // REST endpoint to receive inventory update
        rest("/inventory/Asynchronous/update")
                .post()
                .to("direct:inventoryInput");

        // Initial direct route - send to local seda buffer
        from("direct:inventoryInput")
                .log("Received inventory update request with ${body[items].size()} items")
                .setBody(simple("${body[items]}")) // Extract items list
                .split(body()).streaming()
                .log("Sending to local seda buffer: ${body}")
                .to("seda:localInventoryBuffer") // Step 1: local buffer
                .end()
                .setBody(constant("Inventory update accepted for async processing"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        // Route: from seda to ActiveMQ
        from("seda:localInventoryBuffer?concurrentConsumers=5") // tune concurrency if needed
                .log("Dequeued from Seda: ${body}")
                .to("activemq:queue:processInventory?exchangePattern=InOnly&deliveryMode=2");
    }
}
