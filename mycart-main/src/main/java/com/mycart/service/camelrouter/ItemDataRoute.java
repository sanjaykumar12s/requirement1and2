//package com.mycart.service.camelrouter;
//
//import com.mycart.service.model.ControlRef;
//import org.apache.camel.ProducerTemplate;
//import org.apache.camel.builder.RouteBuilder;
//import org.apache.camel.model.dataformat.JacksonXMLDataFormat;
//import org.apache.camel.spring.spi.SpringTransactionPolicy;
//import org.apache.camel.Exchange;
//import org.springframework.stereotype.Component;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Component
//public class ItemDataRoute extends RouteBuilder {
//
//    @Override
//    public void configure() throws Exception {
//        // Trigger job every hour using Quartz
//        from("quartz2://myTimer?cron=0+0+*+*+*+?")
//                .process(exchange -> {
//                    // Fetch lastProcessTs from ControlRef collection
//                    String controlRefQuery = "{ \"id\": \"1\" }"; // Assuming a single document with ID=1
//                    ControlRef controlRef = exchange.getContext().createProducerTemplate()
//                            .requestBody("mongodb://myMongoDb?database=mycart&collection=controlRef&query=" + controlRefQuery, null, ControlRef.class);
//
//                    String lastProcessTs = controlRef != null ? controlRef.getLastProcessTs() : "1970-01-01T00:00:00Z";
//                    exchange.getIn().setHeader("lastProcessTs", lastProcessTs);
//                })
//                .to("direct:fetchItems"); // Fetch items based on lastProcessTs
//
//        // Fetch items that need processing from MongoDB
//        from("direct:fetchItems")
//                .to("mongodb://myMongoDb?database=mycart&collection=item&query={\"lastupdateTs\": {\"$gt\": \"${header.lastProcessTs}\"}}")
//                .split(body()) // Split the items
//                .parallelProcessing() // Process items in parallel
//                .to("direct:processItem"); // Process each item
//
//        // Process items and send them to respective applications (TrendAnalyzer, ReviewAggregator, StoreFront)
//        JacksonXMLDataFormat jacksonXmlDataFormat = new JacksonXMLDataFormat();
//
//        from("direct:processItem")
//                .choice()
//                .when(header("application").isEqualTo("TrendAnalyzer"))
//                .marshal(jacksonXmlDataFormat) // Correct way to marshal to XML
//                .to("file:/C:/Users/290593/Documents/routefiles/TrendAnalyzer") // Store in the TrendAnalyzer folder
//                .when(header("application").isEqualTo("ReviewAggregator"))
//                .marshal(jacksonXmlDataFormat) // Correct way to marshal to XML
//                .to("file:/C:/Users/290593/Documents/routefiles/ReviewAggregator") // Store in the ReviewAggregator folder
//                .when(header("application").isEqualTo("StoreFront"))
//                .marshal(jacksonXmlDataFormat) // Correct way to marshal to XML
//                .to("file:/C:/Users/290593/Documents/routefiles/StoreFront") // Store in the StoreFront folder
//                .end()
//                .process(exchange -> {
//                    // After processing all items, update the lastProcessTs in ControlRef
//                    String lastProcessTs = "current-timestamp"; // Replace with actual logic for getting the current timestamp
//                    String controlRefQuery = "{ \"id\": \"1\" }"; // Query to find the ControlRef document
//                    String updateQuery = "{ \"$set\": { \"lastProcessTs\": \"" + lastProcessTs + "\" } }"; // Update query for lastProcessTs
//
//                    // Update the lastProcessTs value in MongoDB using an empty Map as the body
//                    Map<String, Object> emptyBody = new HashMap<>(); // Create an empty map
//                    ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate();
//                    producerTemplate.requestBody("mongodb://myMongoDb?database=mycart&collection=controlRef&query=" + controlRefQuery + "&update=" + updateQuery, emptyBody);
//                });
//    }
//}
//
