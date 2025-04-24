package com.mycart.service.processors;

import com.mycart.service.dto.Response;
import org.apache.camel.Exchange;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonInt32;
import org.bson.BsonBoolean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("postNewCategoryProcessor")
public class PostNewCategoryProcessor {

    // Validate method (works directly with BsonDocument)
    public void validate(Exchange exchange) {
        // Get the category as a Map from the body of the exchange
        Map<String, Object> categoryMap = exchange.getIn().getBody(Map.class);

        // Convert the map to a BsonDocument
        BsonDocument category = convertMapToBsonDocument(categoryMap);
        exchange.setProperty("category", category);

        // Extract _id from BsonDocument
        String id = category.containsKey("_id") ? category.getString("_id").getValue().trim() : null;
        if (id == null || id.isEmpty()) {
            setError(exchange, 400, "Invalid Request", "_id is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Extract categoryName from BsonDocument
        String name = category.containsKey("categoryName") ? category.getString("categoryName").getValue().trim() : null;
        if (name == null || name.isEmpty()) {
            setError(exchange, 400, "Invalid Request", "categoryName is required and cannot be blank");
            exchange.setProperty("stopProcessing", true);
            return;
        }

        // Set the categoryId as a header for use in other routes
        exchange.getIn().setHeader("categoryId", id);
    }

    // Check for duplicate category (still using BsonDocument)
    public void checkDuplicate(Exchange exchange) {
        // If the body of the exchange is not null, it means the category already exists
        if (exchange.getIn().getBody() != null) {
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.setProperty("stopProcessing", true);
            exchange.getIn().setBody(new Response(true, "Invalid Request", "Category already exists"));
        }
    }

    // Insert success response
    public void insertSuccess(Exchange exchange) {
        // If the category is successfully inserted, set HTTP response code to 201 (Created)
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 201);
        exchange.getIn().setBody(new Response(false, "Success", "Category inserted successfully"));
    }

    // Set error response
    private void setError(Exchange exchange, int code, String title, String msg) {
        // Set the error response and HTTP status code
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, code);
        exchange.getIn().setBody(new Response(true, title, msg));
    }

    // Convert a Map<String, Object> to a BsonDocument
    private BsonDocument convertMapToBsonDocument(Map<String, Object> map) {
        BsonDocument bsonDocument = new BsonDocument();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

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
