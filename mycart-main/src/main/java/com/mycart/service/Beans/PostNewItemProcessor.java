package com.mycart.service.Beans;

import com.mycart.service.dto.Response;
import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class PostNewItemProcessor {

    public void validate(Exchange exchange) {
        Map<String, Object> item = exchange.getIn().getBody(Map.class);
        exchange.setProperty("item", item); // Store full item for later use

        // Validate _id
        String id = item.get("_id") != null ? item.get("_id").toString() : null;
        if (id == null || id.trim().isEmpty()) {
            throw new ProcessException("_id is required and cannot be blank");
        }

        // Validate itemPrice object
        Map<String, Object> itemPrice = (Map<String, Object>) item.get("itemPrice");
        if (itemPrice == null) {
            throw new ProcessException("itemPrice is required");
        }

        // Validate basePrice and sellingPrice
        Object baseObj = itemPrice.get("basePrice");
        Object sellingObj = itemPrice.get("sellingPrice");

        if (!(baseObj instanceof Integer)) {
            throw new ProcessException("basePrice must be an integer");
        }

        if (!(sellingObj instanceof Integer)) {
            throw new ProcessException("sellingPrice must be an integer");
        }

        int basePrice = (int) baseObj;
        int sellingPrice = (int) sellingObj;

        if (basePrice <= 0 || sellingPrice <= 0) {
            throw new ProcessException("Prices must be greater than zero");
        }

        // Validate stockDetails
        Map<String, Object> stockDetails = (Map<String, Object>) item.get("stockDetails");
        if (stockDetails == null) {
            throw new ProcessException("stockDetails is required");
        }

        for (String key : new String[]{"availableStock", "soldOut", "damaged"}) {
            Object value = stockDetails.get(key);
            if (!(value instanceof Integer)) {
                throw new ProcessException(key + " must be an integer");
            }
        }

        // Validate specialProduct
        Object specialProduct = item.get("specialProduct");
        if (!(specialProduct instanceof Boolean)) {
            throw new ProcessException("specialProduct must be a boolean (true or false)");
        }

        // Validate categoryId
        String categoryId = item.get("categoryId") != null ? item.get("categoryId").toString() : null;
        if (categoryId == null || categoryId.trim().isEmpty()) {
            throw new ProcessException("categoryId is required and cannot be blank");
        }

        // Set headers
        exchange.getIn().setHeader("itemId", id);
        exchange.getIn().setHeader("itemCategoryId", categoryId);
    }

    // Checks if the MongoDB query returned a category document
    public void checkCategory(Exchange exchange) {
        if (exchange.getIn().getBody() == null) {
            throw new ProcessException("Invalid categoryId: Category not found");
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

    // Returns the current timestamp as a formatted string
    public String getCurrentDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public void updateTimestamp(Exchange exchange) {
        Map<String, Object> item = exchange.getProperty("item", Map.class);
        if (item != null) {
            item.put("lastUpdateDate", getCurrentDateTime());
        }
    }
}
