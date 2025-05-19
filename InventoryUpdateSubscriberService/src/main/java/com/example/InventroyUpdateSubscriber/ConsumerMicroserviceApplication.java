package com.example.InventroyUpdateSubscriber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ConsumerMicroserviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConsumerMicroserviceApplication.class, args);
	}

}






























// Final processing route from ActiveMQ queue
//        from("activemq:queue:processInventory")
//                .routeId("inventoryMongoProcessor")
//                // Log the incoming message for inventory processing from ActiveMQ
//                .log("Processing inventory message from ActiveMQ: ${body}")
//
//                .process(exchange -> {
//                    // Put item into a list for further processing
//                    Map<String, Object> item = exchange.getIn().getBody(Map.class);
//                    List<Map<String, Object>> itemList = new ArrayList<>();
//                    itemList.add(item);
//                    // Set the item list into the exchange property for further use in the route
//                    exchange.setProperty("inventoryList", itemList);
//                    // Initialize the success and failure lists
//                    exchange.setProperty("successList", new ArrayList<>());
//                    exchange.setProperty("failureList", new ArrayList<>());
//                })
//
//                // Split the inventory list for processing each item individually
//                .split(simple("${exchangeProperty.inventoryList}")).streaming()
//                .doTry()
//                // Extract and validate the stock fields for the current inventory item
//                .bean("inventoryUpdates", "extractAndValidateStockFields")
//
//                // Set the body of the exchange to the item ID for use in the next step
//                .setBody(simple("${exchangeProperty.itemId}"))
//                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findById")
//                .bean("inventoryUpdates", "computeUnifiedStock")
//                .to("mongodb:myDb?database=mycartdb&collection=item&operation=save")
//                .bean("inventoryUpdates", "trackSuccess")
//
//                .doCatch(Exception.class)
//                .bean("inventoryUpdates", "trackFailure")
//                .end()
//
//                // Reset the error list to an empty list for the next cycle
//                .setProperty("errorList", constant(new ArrayList<>()))
//                .log("Inventory processing completed: ${body}");
//
//    }


// Track successful inventory updates for items
//    public void trackSuccess(Exchange exchange) {
//        String itemId = exchange.getProperty("itemId", String.class);
//        List<Document> successList = exchange.getProperty("successList", List.class);
//
//        Document result = new Document();
//        result.put("itemId", itemId);
//        result.put("status", "success");
//        result.put("message", "Inventory updated successfully for item " + itemId);
//        successList.add(result);
//    }
//
//    // Track failed inventory updates for items
//    public void trackFailure(Exchange exchange) {
//        String itemId = exchange.getProperty("itemId", String.class);
//        String errorMsg = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class).getMessage();
//        List<Document> failureList = exchange.getProperty("failureList", List.class);
//
//        Document result = new Document();
//        result.put("itemId", itemId);
//        result.put("status", "failure");
//        result.put("error", errorMsg);
//        failureList.add(result);
//    }