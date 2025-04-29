package com.example.InventroyUpdateSubscriber.route;

import com.mongodb.MongoClientException;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

@Component
public class InventoryProcessingRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {



        // Final processing route from ActiveMQ queue
        from("activemq:queue:processInventory")
                .routeId("inventoryMongoProcessor")
                // Log the incoming message for inventory processing from ActiveMQ
                .log("Processing inventory message from ActiveMQ: ${body}")

                .process(exchange -> {
                    // Put item into a list for further processing
                    Map<String, Object> item = exchange.getIn().getBody(Map.class);
                    List<Map<String, Object>> itemList = new ArrayList<>();
                    itemList.add(item);
                    // Set the item list into the exchange property for further use in the route
                    exchange.setProperty("inventoryList", itemList);
                    // Initialize the success and failure lists
                    exchange.setProperty("successList", new ArrayList<>());
                    exchange.setProperty("failureList", new ArrayList<>());
                })

                // Split the inventory list for processing each item individually
                .split(simple("${exchangeProperty.inventoryList}")).streaming()
                .doTry()
                // Extract and validate the stock fields for the current inventory item
                .bean("inventoryUpdates", "extractAndValidateStockFields")

                // Set the body of the exchange to the item ID for use in the next step
                .setBody(simple("${exchangeProperty.itemId}"))
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findById")
                .bean("inventoryUpdates", "computeUnifiedStock")
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=save")
                .bean("inventoryUpdates", "trackSuccess")

                .doCatch(Exception.class)
                .bean("inventoryUpdates", "trackFailure")
                .end()

                // Reset the error list to an empty list for the next cycle
                .setProperty("errorList", constant(new ArrayList<>()))
                .log("Inventory processing completed: ${body}");

    }
}
