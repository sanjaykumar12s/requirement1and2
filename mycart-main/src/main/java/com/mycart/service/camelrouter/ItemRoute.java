package com.mycart.service.camelrouter;
import com.mycart.service.dto.Response;
import com.mycart.service.exception.ProcessException;
import com.mycart.service.processors.*;
import org.bson.BsonDocument;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestParamType;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        //REST endpoint to get item by ID

        rest("/mycart/item/{itemId}")
                .get()
                .to("direct:getItemById");

        from("direct:getItemById")
                .log("Fetching item with ID: ${header.itemId}")
                .bean("getItems", "validateItemId")  // Validate itemId
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=findById")
                .choice()
                .when(body().isNull())
                .bean("getItems", "itemNotFound")  // Handle item not found
                .otherwise()
                .log("Item found: ${body}")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .end();

        //-------------------------------------------------------------

        // Define REST endpoint for GET /category/{categoryId}
        rest("/category/{categoryId}")
                .get()
                .param()
                .name("includeSpecial").type(RestParamType.query) // Optional query param
                .description("Include special items").dataType("boolean").defaultValue("")
                .endParam()
                .to("direct:getItemsByCategory");

        // Define internal route to handle fetching category-based items
        from("direct:getItemsByCategory")

                // Step 1: Extract and validate categoryId from request header
                .process(exchange -> {
                    String categoryId = exchange.getIn().getHeader("categoryId", String.class);
                    if (categoryId == null || categoryId.trim().isEmpty()) {
                        // If categoryId is missing, return error response
                        exchange.getIn().setBody(new com.mycart.service.dto.Response(true, "Invalid request", "Missing categoryId in the request URL."));
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                        exchange.setProperty(Exchange.ROUTE_STOP, true); // Flag route to stop
                    } else {
                        // Set categoryId as body for the next Mongo query
                        exchange.getIn().setBody(categoryId);
                    }
                })

                // Step 2: Stop route early if validation failed
                .choice()
                .when(exchangeProperty(Exchange.ROUTE_STOP).isEqualTo(true))
                .log("Route stopped early due to validation error")
                .otherwise()

                // Step 3: Fetch category document from MongoDB by ID
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                // Step 4: Validate if category exists in DB
                .choice()
                .when(body().isNull())
                // Category not found, return error
                .process(exchange -> {
                    exchange.getIn().setBody(new com.mycart.service.dto.Response(true, "Invalid request", "Invalid categoryId"));
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                    exchange.setProperty(Exchange.ROUTE_STOP, true);
                })
                .otherwise()

                // Step 5: Call GetCategory bean to build aggregation pipeline
                .bean("getCategory", "process") // Calls public void process(Exchange) from @Component("getCategory")

                // Step 6: Check if processing should stop due to error in bean
                .choice()
                .when(exchangeProperty(Exchange.ROUTE_STOP).isEqualTo(true))
                .log("Skipping Mongo aggregation due to error.")
                .otherwise()

                // Step 7: Run Mongo aggregation using pipeline built in GetCategory
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=aggregate")

                // Step 8: Format aggregation results
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
                .consumes("application/json")
                .to("direct:postNewItem");

        from("direct:postNewItem")
                .bean("postNewItemProcessor", "validate") // Validate input JSON and extract important fields

                // Stop route early if validation failed
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop()
                .end()

                // Set body to categoryId for category existence check
                .setBody(simple("${header.itemCategoryId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById")

                // Validate that category exists
                .bean("postNewItemProcessor", "checkCategory")

                // Stop route if category was invalid
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)).stop()
                .end()

                // Set body to itemId to check if item already exists
                .setBody(simple("${header.itemId}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=findById")

                .choice()
                .when(body().isNotNull()) // Item already exists → update
                .setProperty("isUpdate", constant(true))
                .setBody(exchangeProperty("item"))
                .setHeader("lastUpdateDate", simple("${bean:postNewItemProcessor.getCurrentDateTime}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=save")
                .bean("postNewItemProcessor", "respondInsertOrUpdate")
                .otherwise()
                .setProperty("isUpdate", constant(false))
                .setBody(exchangeProperty("item"))
                .setHeader("lastUpdateDate", simple("${bean:postNewItemProcessor.getCurrentDateTime}"))
                .to("mongodb:mycartdb?database=mycartdb&collection=item&operation=insert")
                .bean("postNewItemProcessor", "respondInsertOrUpdate")
                .end();



//-------------------------------------------------------------

        // 3. POST new category (API Endpoint Definition)
        rest("/category")
                .post() // Define HTTP POST method for /category endpoint
                .consumes("application/json") // Expecting JSON input
                .to("direct:postNewCategory"); // Route request to direct:postNewCategory

        from("direct:postNewCategory")
                .log("Received new category: ${body}")

                // Step 1: Validate the category data using the postNewCategoryProcessor bean
                .bean("postNewCategoryProcessor", "validate")

                // Conditional check to stop processing if validation fails
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)) // Check if stopProcessing is set to true (validation error)
                .stop()
                .end()

                // Step 2: Query MongoDB to check if the category already exists using categoryId header
                .setBody(simple("${header.categoryId}")) // Set the body to categoryId header
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=findById") // MongoDB query to check if category exists

                // Step 3: Check for category duplication using the postNewCategoryProcessor bean
                .bean("postNewCategoryProcessor", "checkDuplicate") // Calls checkDuplicate method in the processor

                // Conditional check to stop processing if the category already exists
                .choice()
                .when(exchangeProperty("stopProcessing").isEqualTo(true)) // Check if stopProcessing is set to true (duplicate category)
                .stop()
                .end()

                // Step 4: Set the category data in the exchange body and insert it into MongoDB
                .process(exchange -> {
                    // Get the category from the property and set it as the body of the exchange
                    BsonDocument category = exchange.getProperty("category", BsonDocument.class);
                    exchange.getIn().setBody(category);
                })
                .to("mongodb:mycartdb?database=mycartdb&collection=category&operation=insert") // Insert the category into MongoDB

                // Step 5: Respond with success message after insertion
                .bean("postNewCategoryProcessor", "insertSuccess"); // Calls insertSuccess method in the processor to generate the response

        //-----------------------------------------------------------------------------------------
        // Update stock / products count
        rest("/inventory/update")
                .post()
                .to("direct:updateInventory");

        from("direct:updateInventory")
                // Handle exceptions and process error handling
                .onException(ProcessException.class)
                .handled(true)
                .bean("inventoryUpdates", "handleError")
                .end()
                .bean("inventoryUpdates", "validateInventoryRequest")

                // Split the inventory list for processing each item individually
                .split(simple("${exchangeProperty.inventoryList}")).streaming()
                .bean("inventoryUpdates", "extractAndValidateStockFields")

                // Set MongoDB query criteria based on the item ID
                .setHeader("CamelMongoDbCriteria", simple("{ \"_id\": \"${exchangeProperty.itemId}\" }"))

                // Set the body as the item ID for the MongoDB operation
                .setBody(simple("${header.itemId}"))
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=findById")
                .bean("inventoryUpdates", "computeUnifiedStock")

                // Check whether the update should be skipped
                .choice()
                .when(simple("${exchangeProperty.skipUpdate} == true"))
                // Stop processing if update is skipped
                .stop()
                .otherwise()
                // Save the updated item to MongoDB
                .to("mongodb:myMongo?database=mycartdb&collection=item&operation=save")
                .end()
                .end()
                .bean("inventoryUpdates", "prepareFinalResponse");

    }
}