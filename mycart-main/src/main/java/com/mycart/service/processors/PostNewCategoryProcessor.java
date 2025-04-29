package com.mycart.service.processors;

import com.mycart.service.dto.Response;
import org.apache.camel.Exchange;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonInt32;
import org.bson.BsonBoolean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PostNewCategoryProcessor {

    // Step 1: Validate incoming category request
    public void validate(Exchange exchange) {
        // Extract request body as a Map
        Map<String, Object> categoryMap = exchange.getIn().getBody(Map.class);

        // Convert the map to a BSON document
        BsonDocument category = convertMapToBsonDocument(categoryMap);
        exchange.setProperty("category", category);

        // Extract and validate _id
        String id = category.containsKey("_id") ? category.getString("_id").getValue().trim() : null;
        if (id == null || id.isEmpty()) {
            setError(exchange, 400, "Invalid Request", "_id is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Extract and validate categoryName
        String name = category.containsKey("categoryName") ? category.getString("categoryName").getValue().trim() : null;
        if (name == null || name.isEmpty()) {
            setError(exchange, 400, "Invalid Request", "categoryName is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Store the categoryId as a header for use later
        exchange.getIn().setHeader("categoryId", id);
    }

    // Step 2: Check for existing category in MongoDB result
    public void checkDuplicate(Exchange exchange) {
        if (exchange.getIn().getBody() != null) {
            // If document already exists, reject with 400
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.setProperty("stopProcessing", true);
            exchange.getIn().setBody(new Response(true, "Invalid Request", "Category already exists"));
        }
    }

    // Step 3: Build success response after successful MongoDB insert
    public void insertSuccess(Exchange exchange) {
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 201);
        exchange.getIn().setBody(new Response(false, "Success", "Category inserted successfully"));
    }

    // Utility method: Set structured error response
    private void setError(Exchange exchange, int code, String title, String msg) {
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, code);
        exchange.getIn().setBody(new Response(true, title, msg));
    }

    // Utility method: Convert input map into BSON document for MongoDB
    private BsonDocument convertMapToBsonDocument(Map<String, Object> map) {
        BsonDocument bsonDocument = new BsonDocument();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Support basic types (String, Integer, Boolean); can be extended later
            if (value instanceof String) {
                bsonDocument.append(key, new BsonString((String) value));
            } else if (value instanceof Integer) {
                bsonDocument.append(key, new BsonInt32((Integer) value));
            } else if (value instanceof Boolean) {
                bsonDocument.append(key, new BsonBoolean((Boolean) value));
            }
        }
        return bsonDocument;
    }
}
