package com.example.ConsumerMicroservice.route;

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
                .log("Processing inventory message from ActiveMQ: ${body}")
                .process(exchange -> {
                    // Put item into a list for further processing
                    Map<String, Object> item = exchange.getIn().getBody(Map.class);
                    List<Map<String, Object>> itemList = new ArrayList<>();
                    itemList.add(item);
                    exchange.setProperty("inventoryList", itemList);
                    exchange.setProperty("successList", new ArrayList<>());
                    exchange.setProperty("failureList", new ArrayList<>());
                })
                .split(simple("${exchangeProperty.inventoryList}")).streaming()
                .doTry()
                .bean("inventoryUpdates", "extractAndValidateStockFields")
//                .setHeader("CamelMongoDbCriteria", simple("{ \"_id\": \"${exchangeProperty.itemId}\" }"))
//                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findOneByQuery")
                .setBody( simple("${exchangeProperty.itemId}"))
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findById")
                .bean("inventoryUpdates", "computeUnifiedStock")
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=save")
                .bean("inventoryUpdates", "trackSuccess")
                .doCatch(Exception.class)
                .bean("inventoryUpdates", "trackFailure")
                .end()
                .setProperty("errorList", constant(new ArrayList<>()))
                .bean("inventoryUpdates", "saveFinalStatus")
                .to("mongodb:myDb?database=mycartdb&collection=inventory_update_logs&operation=insert")
                .log("Inventory processing completed: ${body}");
    }
}
