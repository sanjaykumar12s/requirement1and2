package com.mycart.service.camelrouter;
import com.mycart.service.dto.Response;
import com.mycart.service.processors.*;
import org.bson.BsonDocument;
import org.bson.Document;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestParamType;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;


@Component
public class ItemRoute extends RouteBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ItemRoute.class);

    @Override
    public void configure() throws ProcessException {
        onException(ProcessException.class)
                .handled(true)
                .log(LoggingLevel.ERROR, "ProcessException occurred: ${exception.message}")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .process(exchange -> {
                    Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);

                    Response errorResponse = new Response();
                    errorResponse.setError(true);
                    errorResponse.setErrorResponse("Invalid Request");
                    errorResponse.setErrMsg(exception.getMessage());

                    exchange.getIn().setBody(errorResponse); // Will be auto-serialized to JSON by Camel
                });
//------------------------------------------------------------------
        // Get item by ID

        rest("/mycart/item/{itemId}")
                .get()
                .to("direct:getItemById");

        from("direct:getItemById")
                .log("Fetching item with ID: ${header.itemId}")
                .bean("itemProcessors", "validateItemId")  // Calls the validateItemId method
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=findById")
                .choice()
                .when(body().isNull())
                .bean("itemProcessors", "itemNotFound")  // Calls the itemNotFound method
                .otherwise()
                .log("Item found: ${body}")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .end();

        //-------------------------------------------------------------

        rest("/category/{categoryId}")
                .get()
                .param()
                .name("includeSpecial").type(RestParamType.query)
                .description("Include special items").dataType("boolean").defaultValue("")
                .endParam()
                .to("direct:getItemsByCategory");

        from("direct:getItemsByCategory")
                .process(exchange -> {
                    String categoryId = exchange.getIn().getHeader("categoryId", String.class);
                    if (categoryId == null || categoryId.trim().isEmpty()) {
                        exchange.getIn().setBody(new com.mycart.service.dto.Response(true, "Invalid request", "Missing categoryId in the request URL."));
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                        exchange.setProperty(Exchange.ROUTE_STOP, true); // Mark route to stop
                    } else {
                        exchange.getIn().setBody(categoryId); // Prepare for Mongo query
                    }
                })

                .choice()
                .when(exchangeProperty(Exchange.ROUTE_STOP).isEqualTo(true))
                .log("Route stopped early due to validation error")
                .otherwise()
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                .choice()
                .when(body().isNull())
                .process(exchange -> {
                    exchange.getIn().setBody(new com.mycart.service.dto.Response(true, "Invalid request", "Invalid categoryId"));
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                    exchange.setProperty(Exchange.ROUTE_STOP, true);
                })
                .otherwise()
                .process(new CategoryProcessor()) // Builds aggregation pipeline (List<Document>)

                // Only run this if body is List<Document>
                .choice()
                .when(exchangeProperty(Exchange.ROUTE_STOP).isEqualTo(true))
                .log("Skipping Mongo aggregation due to error.")
                .otherwise()
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=aggregate")
                .process(exchange -> {
                    List<org.bson.Document> results = exchange.getIn().getBody(List.class);
                    if (results == null || results.isEmpty()) {
                        exchange.getIn().setBody(new org.bson.Document("message", "No items found")
                                .append("items", Collections.emptyList()));
                    } else {
                        exchange.getIn().setBody(results.get(0));
                    }
                })
                .endChoice()
                .endChoice()
                .end();

//-----------------------------------------------------------------

        //todo : post items
        rest("/items")
                .post()
                .consumes("application/json")
                .to("direct:postNewItem");

        from("direct:postNewItem")
                .bean("postNewItemProcessor", "validate")
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop().end()

                .setBody(simple("${header.itemCategoryId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")
                .bean("postNewItemProcessor", "checkCategory")
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop().end()

                .setBody(simple("${header.itemId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=findById")

                .choice()
                .when(body().isNotNull()) // update
                .setBody(exchangeProperty("item"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=save")
                .process(e -> {
                    PostNewItemProcessor p = e.getContext().getRegistry()
                            .lookupByNameAndType("postNewItemProcessor", PostNewItemProcessor.class);
                    p.respondInsertOrUpdate(e, true);
                })
                .otherwise() // insert
                .setBody(exchangeProperty("item"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=insert")
                .process(e -> {
                    PostNewItemProcessor p = e.getContext().getRegistry()
                            .lookupByNameAndType("postNewItemProcessor", PostNewItemProcessor.class);
                    p.respondInsertOrUpdate(e, false);
                });
//-------------------------------------------------------------
        // post new category

        // 3. POST new category (unchanged)
        rest("/category")
                .post()
                .consumes("application/json")
                .to("direct:postNewCategory");

        from("direct:postNewCategory")
                .log("Received new category: ${body}")
                .bean("postNewCategoryProcessor", "validate")
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true))
                .stop()
                .end()

                .setBody(simple("${header.categoryId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")
                .bean("postNewCategoryProcessor", "checkDuplicate")
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true))
                .stop()
                .end()

                .process(exchange -> {
                    // Get the category from the property and set it as the body of the exchange
                    BsonDocument category = exchange.getProperty("category", BsonDocument.class);
                    exchange.getIn().setBody(category);
                })
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=insert")
                .bean("postNewCategoryProcessor", "insertSuccess");

        //-----------------------------------------------------------------------------------------
        // Update stock / products count

        rest("/inventory/update")
                .post()
                .to("direct:updateInventory");

        from("direct:updateInventory")
                .onException(ProcessException.class)
                .handled(true)
                .bean("inventoryUpdates", "handleError")
                .end()
                .bean("inventoryUpdates", "validateInventoryRequest")
                .split(simple("${exchangeProperty.inventoryList}")).streaming()
                .bean("inventoryUpdates", "extractAndValidateStockFields")
                .setHeader("CamelMongoDbCriteria", simple("{ \"_id\": \"${exchangeProperty.itemId}\" }"))
                .setBody(simple("${header.itemId}"))
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=findById")
                .bean("inventoryUpdates", "computeUnifiedStock")
                .choice()
                .when(simple("${exchangeProperty.skipUpdate} == true"))
                .stop()
                .otherwise()
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=save")
                .end()
                .end()
                .bean("inventoryUpdates", "prepareFinalResponse");
    }
}