package com.mycart.service.Beans;

import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import org.bson.Document;


import java.util.LinkedHashMap;

@Component
public class GetItems {

    // Validates itemId in the request header

    public void validateItemId(Exchange exchange) {
        String itemId = (String) exchange.getIn().getHeader("itemId");
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new ProcessException("Missing or empty itemId in the request.");
        }
        exchange.getIn().setBody(itemId);
    }

    // Throws exception if item is not found

    public void itemNotFound(Exchange exchange) {
        String itemId = (String) exchange.getIn().getHeader("itemId");
        throw new ProcessException("Item not found for ID: " + itemId);
    }

    public void enrichItemWithCategoryName(Exchange exchange) {
        // Get category document from body
        Document category = exchange.getIn().getBody(Document.class);

        // Extract categoryName (or set Unknown if category is null)
        String categoryName = (category != null) ? category.getString("categoryName") : "Unknown";

        // Get the original item document from exchange property
        Document item = exchange.getProperty("item", Document.class);

        // Remove categoryId field from item
        item.remove("categoryId");

        // Create LinkedHashMap to preserve order of fields
        LinkedHashMap<String, Object> orderedItem = new LinkedHashMap<>();

        // Insert fields in desired order
        orderedItem.put("_id", item.getString("_id"));
        orderedItem.put("name", item.getString("name"));
        orderedItem.put("categoryName", categoryName);

        // Add remaining fields preserving the order except _id, name, categoryId
        for (String key : item.keySet()) {
            if (!key.equals("_id") && !key.equals("name") && !key.equals("categoryId")) {
                orderedItem.put(key, item.get(key));
            }
        }

        // Set the new ordered map as the message body
        exchange.getIn().setBody(orderedItem);
    }
}
