package com.mycart.service.Beans;

import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

@Component
public class InventoryUpdates {

    // Safely parse an object to integer, used for soldOut, damaged, availableStock

    // Convert incoming body into Document object (either from Map or Document)
    private Document getDocumentFromBody(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof Document) {
            return (Document) body;  // Return the body as Document if it is already of that type
        } else if (body instanceof Map) {
            return new Document((Map<String, Object>) body);  // Convert the Map into Document if it's of Map type
        } else {
            // Raise an error if the body is neither Document nor Map
            throw new ProcessException("You are updating an Invalid Item  " + (body != null ? body.getClass() : " Item Not found in DB"));
        }
    }

    // Handle custom errors during update and add them to errorList
    public void handleError(Exchange exchange) {
        ProcessException exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, ProcessException.class);
        List<Document> errorList = exchange.getProperty("errorList", List.class);

        // Initialize error list if not present
        if (errorList == null) {
            errorList = new ArrayList<>();
            exchange.setProperty("errorList", errorList);
        }

        // Create a new error document
        //org.bson.Document from MongoDB) is created to hold error info.
        Document error = new Document();
        error.put("itemId", exchange.getProperty("itemId"));
        error.put("message", exception.getMessage());
        errorList.add(error);  // Add to error list

        exchange.setProperty("skipUpdate", true);  // Skip further processing for this item
    }

    // Prepare a final success + error response to be returned after route is complete
    public void prepareFinalResponse(Exchange exchange) {
        List<Document> errors = exchange.getProperty("errorList", List.class);
        List<Document> successes = exchange.getProperty("successList", List.class);

        Document response = new Document();
        response.put("message", "Inventory update completed");
        response.put("successfulUpdates", successes);  // Add successful updates to response
        response.put("errors", errors);  // Add errors to response

        // Logging summary of success and errors
        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=INFO",
                "Inventory update details:\n" +
                        "Successful updates: " + successes.size() + "\n" +
                        "Errors: " + errors.size() + "\n" +
                        "Successful Items: " + successes + "\n" +
                        "Error Items: " + errors);

        // Log full response for debugging
        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=DEBUG", response);

        // Set HTTP response code based on the number of errors
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, errors.isEmpty() ? 200 : 400);
        exchange.getIn().setBody(response);  // Set response body
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");  // Set content type
    }

    // Validate that the inventory input JSON contains a valid 'items' list
    public void validateInventoryRequest(Exchange exchange) {
        Document body = getDocumentFromBody(exchange);

        // Check if 'items' field is present and not empty
        if (!body.containsKey("items") || body.get("items") == null) {
            throw new ProcessException("Invalid inventory format: 'items' field is missing or empty.");
        }

        List<Document> items = new ArrayList<>();
        // Convert all items in the 'items' list to Document objects
        for (Object obj : (List<?>) body.get("items")) {
            if (obj instanceof Map) {
                items.add(new Document((Map<String, Object>) obj));
            }
        }

        // Check if the items list is empty after conversion
        if (items.isEmpty()) {
            throw new ProcessException("Invalid inventory format: 'items' list is empty or invalid.");
        }

        // Set properties for future use in route
        exchange.setProperty("inventoryList", items);
        exchange.setProperty("errorList", new ArrayList<Document>());
        exchange.setProperty("successList", new ArrayList<Document>());
        exchange.setProperty("failureList", new ArrayList<Document>());
    }

    // Extract item ID, soldOut and damaged values from each item document
    public void extractAndValidateStockFields(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);

        // Validate if the item and its stock details are present
        if (item == null || item.get("_id") == null || item.get("stockDetails") == null) {
            throw new ProcessException("Item ID or stock details are missing.");
        }

        String id = item.getString("_id");
        Object stockDetailsObj = item.get("stockDetails");
        Document stock = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);

        int soldOut = (int) stock.get("soldOut");
        int damaged = (int) stock.get("damaged");

        exchange.setProperty("itemId", id);
        exchange.setProperty("soldOut", soldOut);
        exchange.setProperty("damaged", damaged);
    }

    // Compute final availableStock and update lastUpdateDate
    public void computeUnifiedStock(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);

        // Validate if the item and stock details are found
        if (item == null) throw new ProcessException("Item not found in DB.");

        Object stockDetailsObj = item.get("stockDetails");
        Document stockDetails = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);

        // Validate if stock details exist for the item
        if (stockDetails == null) throw new ProcessException("Stock details are missing for item");

        int availableStock = (int) stockDetails.get("availableStock");
        int existingSoldOut = (int) stockDetails.get("soldOut");
        int existingDamaged = (int) stockDetails.get("damaged");

        int soldOut = exchange.getProperty("soldOut", Integer.class);
        int damaged = exchange.getProperty("damaged", Integer.class);

        // Validation if soldOut + damaged exceed availableStock
        if (availableStock == 0)
            throw new ProcessException("Zero available stock");
        if ((soldOut + damaged) > availableStock)
            throw new ProcessException("Sold out and damaged exceed available stock for item ID: " + item.get("_id"));

        // Calculate the new available stock and update the fields
        stockDetails.put("availableStock", Math.max(0, availableStock - soldOut - damaged));
        stockDetails.put("soldOut", existingSoldOut + soldOut);
        stockDetails.put("damaged", existingDamaged + damaged);
        item.put("stockDetails", stockDetails);
        item.put("lastUpdateDate", LocalDate.now().toString());  // Update the last updated date

        // Store the updated item in exchange properties for later use
        exchange.setProperty("updatedItem", item);

        //Update the success list
        List<Document> successList = exchange.getProperty("successList", List.class);
        if (successList == null) {
            successList = new ArrayList<>();
            exchange.setProperty("successList", successList);
        }

        // Add a success item to the success list
        Document successItem = new Document();
        successItem.put("itemId", item.get("_id"));
        successItem.put("availableStock", stockDetails.get("availableStock"));
        successList.add(successItem);

        exchange.getIn().setBody(item);  // Set the updated item as the message body
    }
}
