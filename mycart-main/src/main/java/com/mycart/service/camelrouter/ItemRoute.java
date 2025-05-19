package com.mycart.service.camelrouter;
import com.mycart.service.dto.Response;
import com.mycart.service.exception.ProcessException;
import com.mycart.service.model.Category;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


@Component
public class ItemRoute extends RouteBuilder {

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
        //REST endpoint to get item by ID

        //  Define REST endpoint to fetch item by ID
        rest("/mycart/item/{itemId}")
                   .get()
//                .to("direct:getItemById");
                   .to("direct:getItemWithCategoryName");


        from("direct:getItemWithCategoryName")
                .routeId("getItemWithCategoryNameRoute")

                // Validate itemId from header and set as body for Mongo query
                .bean("getItems", "validateItemId")

                // Fetch item by _id (itemId)
                .to("mongodb:myDb?database=mycartdb&collection=item&operation=findById")

                .choice()
                .when(body().isNull())
                .bean("getItems", "itemNotFound")
                .end()

                // Extract categoryId and store item and categoryId in exchange properties
                .process(exchange -> {
                    org.bson.Document item = exchange.getIn().getBody(org.bson.Document.class);
                    String categoryId = item.getString("categoryId");
                    exchange.setProperty("item", item);
                    exchange.setProperty("categoryId", categoryId);
                })

                // Query category by _id = categoryId
                .setBody(simple("${exchangeProperty.categoryId}"))
                .to("mongodb:myDb?database=mycartdb&collection=category&operation=findById")

                // Use bean method to enrich item with categoryName replacing categoryId and reorder fields
                .bean("getItems", "enrichItemWithCategoryName")

                .log("Final item with categoryName: ${body}");




        //-------------------------------------------------------------

        // Define REST endpoint for GET /category/{categoryId}
        rest("/category/{categoryId}")
                .get()
                .to("direct:getItemsByCategory");

        // Define internal route to handle fetching category-based items
        from("direct:getItemsByCategory")
                .routeId("getItemsByCategory")
                .setBody(simple("${header.categoryId}"))

                //  Fetch category document from MongoDB by ID
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                //  Validate if category exists in DB
                .choice()
                .when(body().isNull())
                // Category not found, return error
                .process(exchange -> {
                    exchange.getIn().setBody(new Response(true, "Invalid request", "Invalid categoryId"));
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                    exchange.setProperty(Exchange.ROUTE_STOP, true);
                })
                .otherwise()

                //  Call GetCategory bean to build aggregation pipeline
                .bean("getCategory", "process") // Calls public void process(Exchange) from @Component("getCategory")

                //  Check if processing should stop due to error in bean
                .choice()
                .when(exchangeProperty(Exchange.ROUTE_STOP).isEqualTo(true))
                .log("Skipping Mongo aggregation due to error.")
                .otherwise()

                //  Run Mongo aggregation using pipeline built in GetCategory
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=aggregate")

                //  Format aggregation results
                .process(exchange -> {
                    List<org.bson.Document> results = exchange.getIn().getBody(List.class);
                    if (results == null || results.isEmpty()) {
                        // No items found, return empty result
                        exchange.getIn().setBody(new org.bson.Document("message", "No items found")
                                .append("items", Collections.emptyList()));
                    } else {
                        // Return first aggregation result (grouped by category)
                        exchange.getIn().setBody(results.get(0));
                    }
                })

                .endChoice()
                .endChoice()
                .endChoice()
                .end();


//-----------------------------------------------------------------
        // REST endpoint to POST new items
        rest("/items")
                .post()
                .to("direct:postNewItem");

        from("direct:postNewItem")
                .routeId("postNewItem")
                //  Validate input JSON and extract important fields
                .bean("postNewItemProcessor", "validate")

                //  Stop route early if validation failed
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop()
                .end()

                //  Set body to categoryId for category existence check
                .setBody(simple("${header.itemCategoryId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                //  Validate that category exists
                .bean("postNewItemProcessor", "checkCategory")

                //  Stop route if category was invalid
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop()
                .end()

                //  Set body to itemId to check if item already exists
                .setBody(simple("${header.itemId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=findById")

                //  If item exists, update it
                .choice()
                .when(body().isNotNull())
                .setProperty("isUpdate", constant(true)) // Mark as update
                .setBody(exchangeProperty("item"))
                .bean("postNewItemProcessor", "updateTimestamp")

                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=save")
                .bean("postNewItemProcessor", "respondInsertOrUpdate")

                //  Else, insert new item
                .otherwise()
                .setProperty("isUpdate", constant(false)) // Mark as insert
                .setBody(exchangeProperty("item"))
                .bean("postNewItemProcessor", "updateTimestamp")
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=insert")
                .bean("postNewItemProcessor", "respondInsertOrUpdate")
                .end();

//-------------------------------------------------------------

        // 3. POST new category (API Endpoint Definition)
        rest("/category")
                .post() // Define HTTP POST method for /category endpoint
                .to("direct:postNewCategory"); // Route request to direct:postNewCategory

        from("direct:postNewCategory")
                .routeId("postNewCategory")
                .log("Received new category: ${body}")

                .bean("postNewCategoryProcessor", "validate")
                .choice().when(exchangeProperty("stopProcessing").isEqualTo(true))
                .stop()
                .end()

                // Check if category with same ID exists
                .setBody(simple("${header.categoryId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                .bean("postNewCategoryProcessor", "checkDuplicate")
                .choice().when(exchangeProperty("stopProcessing").isEqualTo(true)).stop().end()

                // Insert the category
                .process(exchange -> {
                    Map<String, String> category = exchange.getProperty("category", Map.class);
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
                .routeId("updateInventory")
                //  Handle exceptions using custom error processor
                .onException(ProcessException.class)
                .handled(true)
                .bean("inventoryUpdates", "handleError")
                .end()

                //  Validate the incoming inventory update request
                .bean("inventoryUpdates", "validateInventoryRequest")

                // Split inventory list to process each item separately
                .split(simple("${exchangeProperty.inventoryList}")).streaming()

                //  Extract required stock fields (soldOut, damaged) and validate them
                .bean("inventoryUpdates", "extractAndValidateStockFields")

                //  Set MongoDB query criteria using item ID
                .setHeader("CamelMongoDbCriteria", simple("{ \"_id\": \"${exchangeProperty.itemId}\" }"))

                //  Set body to item ID for MongoDB find operation
                .setBody(simple("${header.itemId}"))
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=findById")

                //  Compute unified available stock (availableStock - soldOut - damaged)
                .bean("inventoryUpdates", "computeUnifiedStock")

                //  Check if update should be skipped (e.g., invalid or unchanged)
                .choice()
                .when(simple("${exchangeProperty.skipUpdate} == true"))
                //  Skip update and stop processing
                .stop()
                .otherwise()
                //  Save updated item back to MongoDB
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=save")
                .end()
                .end()

                //  Prepare final aggregated response after processing all items
                .bean("inventoryUpdates", "prepareFinalResponse");
    }
    }