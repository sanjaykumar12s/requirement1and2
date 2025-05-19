package com.example.InventroyUpdateSubscriber.route;

import com.example.InventroyUpdateSubscriber.exception.ProcessException;
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

        from("activemq:queue:processInventory")
                .routeId("inventoryMongoProcessor")
                .log("Processing inventory message from ActiveMQ: ${body}")

                .process(exchange -> {
                    Map<String, Object> item = exchange.getIn().getBody(Map.class);
                    List<Map<String, Object>> itemList = new ArrayList<>();
                    itemList.add(item);
                    exchange.setProperty("inventoryList", itemList);
                })

                .split(simple("${exchangeProperty.inventoryList}")).streaming()
                .doTry()
                .bean("inventoryUpdates", "extractAndValidateStockFields")
                .setBody(simple("${exchangeProperty.itemId}"))
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findById")
                .bean("inventoryUpdates", "computeUnifiedStock")
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=save")
                .doCatch(ProcessException.class)
                .log("Error processing inventory item: ${exception.message}")
                .end()

                .log("Inventory processing completed: ${body}");
    }

}
