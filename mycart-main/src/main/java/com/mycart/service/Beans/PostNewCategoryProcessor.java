package com.mycart.service.Beans;

import com.mycart.service.dto.Response;
import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PostNewCategoryProcessor {

    // Validate category input
    public void validate(Exchange exchange) {
        Map<String, String> categoryMap = exchange.getIn().getBody(Map.class);

        // Validate _id
        Object idObj = categoryMap.get("_id");
        String id = null;
        if (idObj instanceof String) {
            id = ((String) idObj).trim();
        }

        if (!(idObj instanceof String)) {
            throw new ProcessException("_id must be a string.");
        }
        if (id == null || id.isEmpty()) {
            throw new ProcessException("_id is required and cannot be blank");
        }

        // Validate categoryName
        Object nameObj = categoryMap.get("categoryName");
        String name = null;
        if (nameObj instanceof String) {
            name = ((String) nameObj).trim();
        }
        if (name == null || name.isEmpty()) {
            throw new ProcessException("categoryName is required and cannot be blank");
        }

        // Store values for later
        exchange.setProperty("category", categoryMap);
        exchange.getIn().setHeader("categoryId", id);
    }

    public void checkDuplicate(Exchange exchange) {
        if (exchange.getIn().getBody() != null) {
            throw new ProcessException("Category already exists");
        }
    }

    public void insertSuccess(Exchange exchange) {
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 201);
        exchange.getIn().setBody(new Response(false, "Success", "Category inserted successfully"));
    }
}
