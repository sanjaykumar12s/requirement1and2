package com.example.InventroyUpdateSubscriber.processor;

import com.example.InventroyUpdateSubscriber.exception.ProcessException;
import org.apache.camel.Exchange;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

@Component
public class InventoryUpdates {



    // Extract a Document from the body of the Exchange, either from a Map or Document
    private Document getDocumentFromBody(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof Document) {
            // Return the body as a Document if it's already a Document type
            return (Document) body;
        } else if (body instanceof Map) {
            // Convert the Map to a Document if it's of Map type
            return new Document((Map<String, Object>) body);
        } else {
            // Throw an exception if the body is of an unsupported type
            throw new ProcessException("Unsupported body type: " + (body != null ? body.getClass() : "null"));
        }
    }

    // Handle any errors that occur during the inventory update process
    public void handleError(Exchange exchange) {
        // Get the ProcessException thrown during the route processing
        ProcessException exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, ProcessException.class);
        List<Document> errorList = exchange.getProperty("errorList", List.class);

        if (errorList == null) {
            errorList = new ArrayList<>();
            exchange.setProperty("errorList", errorList);
        }

        // Create an error Document and add it to the errorList
        Document error = new Document();
        error.put("itemId", exchange.getProperty("itemId"));
        error.put("message", exception.getMessage());
        errorList.add(error);

        // Mark the update as skipped for this item
        exchange.setProperty("skipUpdate", true);
    }

    // Prepare the final response after processing inventory updates
    public void prepareFinalResponse(Exchange exchange) {
        List<Document> errors = exchange.getProperty("errorList", List.class);
        List<Document> successes = exchange.getProperty("successList", List.class);

        Document response = new Document();
        response.put("message", "Inventory update completed");
        response.put("successfulUpdates", successes);
        response.put("errors", errors);

        // Log success and error details
        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=INFO",
                "Inventory update details:\n" +
                        "Successful updates: " + successes.size() + "\n" +
                        "Errors: " + errors.size() + "\n" +
                        "Successful Items: " + successes + "\n" +
                        "Error Items: " + errors);

        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=DEBUG", response);

        // Set the HTTP response code based on errors and successes
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, errors.isEmpty() ? 200 : 400);
        exchange.getIn().setBody(response);
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
    }

    // Validate the incoming inventory request, ensuring the presence of 'items' field
    public void validateInventoryRequest(Exchange exchange) {
        Document body = getDocumentFromBody(exchange);

        // Check if the 'items' field exists and is not null
        if (!body.containsKey("items") || body.get("items") == null) {
            throw new ProcessException("Invalid inventory format: 'items' field is missing or empty.");
        }

        List<Document> items = new ArrayList<>();
        for (Object obj : (List<?>) body.get("items")) {
            if (obj instanceof Map) {
                items.add(new Document((Map<String, Object>) obj));
            }
        }

        if (items.isEmpty()) {
            throw new ProcessException("Invalid inventory format: 'items' list is empty or invalid.");
        }

        // Store the items in the exchange properties for later use
        exchange.setProperty("inventoryList", items);
        exchange.setProperty("errorList", new ArrayList<Document>());
        exchange.setProperty("successList", new ArrayList<Document>());
        exchange.setProperty("failureList", new ArrayList<Document>());
    }

    // Extract and validate the stock fields for each inventory item
    public void extractAndValidateStockFields(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);

        // Check if the item ID and stock details are available in the item document
        if (item == null || item.get("_id") == null || item.get("stockDetails") == null) {
            throw new ProcessException("Item ID or stock details are missing.");
        }

        String id = item.getString("_id");
        Object stockDetailsObj = item.get("stockDetails");
        Document stock = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);

        // Parse sold-out and damaged quantities
        int soldOut = (int) stock.get("soldOut");
        int damaged = (int) stock.get("damaged");

        // Store these values in the exchange properties for later use
        exchange.setProperty("itemId", id);
        exchange.setProperty("soldOut", soldOut);
        exchange.setProperty("damaged", damaged);
    }

    // Compute unified stock values for inventory items, considering sold out and damaged items
    public void computeUnifiedStock(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);
        Object stockDetailsObj = item.get("stockDetails");
        Document stockDetails = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);
        if (stockDetails == null) throw new ProcessException("Stock details are missing for item");

        int availableStock = (int) stockDetails.get("availableStock");
        int existingSoldOut = (int) stockDetails.get("soldOut");
        int existingDamaged = (int) stockDetails.get("damaged");

        int soldOut = exchange.getProperty("soldOut", Integer.class);
        int damaged = exchange.getProperty("damaged", Integer.class);

        if (availableStock == 0)
            throw new ProcessException("Zero available stock");
        if ((soldOut + damaged) > availableStock)
            throw new ProcessException("Sold out and damaged exceed available stock for item ID: " + item.get("_id"));

        stockDetails.put("availableStock", Math.max(0, availableStock - soldOut - damaged));
        stockDetails.put("soldOut", existingSoldOut + soldOut);
        stockDetails.put("damaged", existingDamaged + damaged);
        item.put("stockDetails", stockDetails);
        item.put("lastUpdateDate", LocalDate.now().toString());

        exchange.setProperty("updatedItem", item);

        List<Document> successList = exchange.getProperty("successList", List.class);
        if (successList == null) {
            successList = new ArrayList<>();
            exchange.setProperty("successList", successList);
        }

        Document successItem = new Document();
        successItem.put("itemId", item.get("_id"));
        successItem.put("availableStock", stockDetails.get("availableStock"));
        successList.add(successItem);

        exchange.getIn().setBody(item);
    }
}
