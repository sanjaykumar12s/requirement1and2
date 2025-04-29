package com.mycart.service.processors;

import com.mycart.service.dto.Response;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
@Component
public class PostNewItemProcessor {

    // Validates the incoming item JSON and extracts necessary fields
    public void validate(Exchange exchange) {
        Map<String, Object> item = exchange.getIn().getBody(Map.class);
        exchange.setProperty("item", item); // Store full item for later use

        Object id = item.get("_id");
        if (id == null || id.toString().trim().isEmpty()) {
            setError(exchange, 400, "Invalid Request", "_id is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Validate that itemPrice object is present
        Map<String, Object> itemPrice = (Map<String, Object>) item.get("itemPrice");
        if (itemPrice == null) {
            setError(exchange, 400, "Invalid Request", "itemPrice is required");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Ensure basePrice and sellingPrice are present and > 0
        Object base = itemPrice.get("basePrice");
        Object selling = itemPrice.get("sellingPrice");

        if (base == null || selling == null) {
            setError(exchange, 400, "Invalid Request", "basePrice and sellingPrice are required");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        double basePrice = ((Number) base).doubleValue();
        double sellingPrice = ((Number) selling).doubleValue();

        if (basePrice <= 0 || sellingPrice <= 0) {
            setError(exchange, 400, "Invalid Request", "Prices must be greater than zero");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Set headers for later use
        exchange.getIn().setHeader("itemId", id.toString());

        Object categoryId = item.get("categoryId");
        if (categoryId == null || categoryId.toString().trim().isEmpty()) {
            setError(exchange, 400, "Invalid Request", "categoryId is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        exchange.getIn().setHeader("itemCategoryId", categoryId.toString());
    }

    // Checks if the MongoDB query returned a category document
    public void checkCategory(Exchange exchange) {
        if (exchange.getIn().getBody() == null) {
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.setProperty("stopProcessing", true);
            exchange.getIn().setBody(new Response(true, "Invalid Request", "Invalid categoryId: Category not found"));
        }
    }

    // Prepares HTTP response depending on insert/update
    public void respondInsertOrUpdate(Exchange exchange) {
        Boolean isUpdate = exchange.getProperty("isUpdate", Boolean.class);
        if (Boolean.TRUE.equals(isUpdate)) {
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getIn().setBody(new Response(false, "Success", "Item updated successfully"));
        } else {
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 201);
            exchange.getIn().setBody(new Response(false, "Success", "Item inserted successfully"));
        }
    }


    // Helper to set error response
    private void setError(Exchange exchange, int code, String title, String msg) {
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, code);
        exchange.getIn().setBody(new Response(true, title, msg));
    }

    // Returns the current timestamp as a formatted string
    public String getCurrentDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
