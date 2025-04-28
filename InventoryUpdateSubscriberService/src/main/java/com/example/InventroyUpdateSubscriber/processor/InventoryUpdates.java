package com.example.InventroyUpdateSubscriber.processor;


import com.example.InventroyUpdateSubscriber.exception.ProcessException;
import org.apache.camel.Exchange;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

@Component("inventoryUpdates")
public class InventoryUpdates {

    private int parseIntSafe(Object value) {
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            throw new ProcessException("Invalid number format in stock details");
        }
    }

    private Document getDocumentFromBody(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof Document) {
            return (Document) body;
        } else if (body instanceof Map) {
            return new Document((Map<String, Object>) body);
        } else {
            throw new ProcessException("Unsupported body type: " + (body != null ? body.getClass() : "null"));
        }
    }

    public void handleError(Exchange exchange) {
        ProcessException exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, ProcessException.class);
        List<Document> errorList = exchange.getProperty("errorList", List.class);
        if (errorList == null) {
            errorList = new ArrayList<>();
            exchange.setProperty("errorList", errorList);
        }

        Document error = new Document();
        error.put("itemId", exchange.getProperty("itemId"));
        error.put("message", exception.getMessage());
        errorList.add(error);

        exchange.setProperty("skipUpdate", true);
    }

    public void prepareFinalResponse(Exchange exchange) {
        List<Document> errors = exchange.getProperty("errorList", List.class);
        List<Document> successes = exchange.getProperty("successList", List.class);

        Document response = new Document();
        response.put("message", "Inventory update completed");
        response.put("successfulUpdates", successes);
        response.put("errors", errors);

        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=INFO",
                "Inventory update details:\n" +
                        "Successful updates: " + successes.size() + "\n" +
                        "Errors: " + errors.size() + "\n" +
                        "Successful Items: " + successes + "\n" +
                        "Error Items: " + errors);

        exchange.getContext().createProducerTemplate().sendBody("log:finalResponseLog?level=DEBUG", response);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, errors.isEmpty() ? 200 : 400);
        exchange.getIn().setBody(response);
        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
    }

    public void validateInventoryRequest(Exchange exchange) {
        Document body = getDocumentFromBody(exchange);
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

        exchange.setProperty("inventoryList", items);
        exchange.setProperty("errorList", new ArrayList<Document>());
        exchange.setProperty("successList", new ArrayList<Document>());
        exchange.setProperty("failureList", new ArrayList<Document>());
    }

    public void extractAndValidateStockFields(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);
        if (item == null || item.get("_id") == null || item.get("stockDetails") == null) {
            throw new ProcessException("Item ID or stock details are missing.");
        }

        String id = item.getString("_id");
        Object stockDetailsObj = item.get("stockDetails");
        Document stock = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);

        int soldOut = parseIntSafe(stock.get("soldOut"));
        int damaged = parseIntSafe(stock.get("damaged"));

        exchange.setProperty("itemId", id);
        exchange.setProperty("soldOut", soldOut);
        exchange.setProperty("damaged", damaged);
    }

    public void flattenItems(Exchange exchange) {
        Document body = getDocumentFromBody(exchange);
        Object rawItems = body.get("items");
        List<Document> flatItemList = new ArrayList<>();

        if (rawItems instanceof List<?>) {
            for (Object group : (List<?>) rawItems) {
                if (group instanceof List<?>) {
                    for (Object item : (List<?>) group) {
                        if (item instanceof Map) {
                            flatItemList.add(new Document((Map<String, Object>) item));
                        }
                    }
                } else if (group instanceof Map) {
                    flatItemList.add(new Document((Map<String, Object>) group));
                }
            }
        }

        exchange.getIn().setBody(flatItemList);
    }

    public void computeUnifiedStock(Exchange exchange) {
        Document item = getDocumentFromBody(exchange);
        if (item == null) throw new ProcessException("Item not found in DB.");

        Object stockDetailsObj = item.get("stockDetails");
        Document stockDetails = stockDetailsObj instanceof Document ? (Document) stockDetailsObj : new Document((Map<String, Object>) stockDetailsObj);
        if (stockDetails == null) throw new ProcessException("Stock details are missing for item");

        int availableStock = parseIntSafe(stockDetails.get("availableStock"));
        int existingSoldOut = parseIntSafe(stockDetails.get("soldOut"));
        int existingDamaged = parseIntSafe(stockDetails.get("damaged"));

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

    public void trackSuccess(Exchange exchange) {
        String itemId = exchange.getProperty("itemId", String.class);
        List<Document> successList = exchange.getProperty("successList", List.class);

        Document result = new Document();
        result.put("itemId", itemId);
        result.put("status", "success");
        result.put("message", "Inventory updated successfully for item " + itemId);
        successList.add(result);
    }

    public void trackFailure(Exchange exchange) {
        String itemId = exchange.getProperty("itemId", String.class);
        String errorMsg = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class).getMessage();
        List<Document> failureList = exchange.getProperty("failureList", List.class);

        Document result = new Document();
        result.put("itemId", itemId);
        result.put("status", "failure");
        result.put("error", errorMsg);
        failureList.add(result);
    }
}