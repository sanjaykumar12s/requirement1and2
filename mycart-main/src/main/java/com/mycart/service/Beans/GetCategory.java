package com.mycart.service.Beans;

import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GetCategory {

    // Entry point for processing category request
    public void process(Exchange exchange) throws Exception {

        // Extract categoryId from header
        String categoryId = exchange.getIn().getHeader("categoryId", String.class);
        if (categoryId == null || categoryId.trim().isEmpty()) {
            throw new ProcessException("categoryId header is required and cannot be empty.");
        }

        // Extract and validate includeSpecial flag
        String includeSpecial = exchange.getIn().getHeader("includeSpecial", String.class);
        if (includeSpecial != null) {
            includeSpecial = includeSpecial.trim();
            if (includeSpecial.isEmpty() ||
                    (!includeSpecial.equalsIgnoreCase("true") && !includeSpecial.equalsIgnoreCase("false"))) {
                throw new ProcessException("Invalid value for includeSpecial. Allowed values: true, false.");
            }
        }

        // Build MongoDB aggregation pipeline
        List<Document> pipeline = new ArrayList<>();

        // Add match stage for categoryId and optional specialProduct filter
        Document matchStage = new Document("categoryId", categoryId);
        if ("true".equalsIgnoreCase(includeSpecial)) {
            matchStage.append("specialProduct", true);
        } else if ("false".equalsIgnoreCase(includeSpecial)) {
            matchStage.append("specialProduct", false);
        }
        pipeline.add(new Document("$match", matchStage));

        // Lookup category details from 'category' collection
        pipeline.add(new Document("$lookup", new Document()
                .append("from", "category")
                .append("localField", "categoryId")
                .append("foreignField", "_id")
                .append("as", "categoryDetails")));

        // Unwind categoryDetails array
        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$categoryDetails")
                .append("preserveNullAndEmptyArrays", true)));

        // Group the items by categoryId and reshape the output
        pipeline.add(new Document("$group", new Document()
                .append("_id", "$categoryId")
                .append("categoryName", new Document("$first", "$categoryDetails.categoryName"))
                .append("categoryDepartment", new Document("$first", "$categoryDetails.categoryDepartment"))
                .append("items", new Document("$push", new Document()
                        .append("_id", "$_id")
                        .append("name", "$name")
                        .append("categoryId", "$categoryId")
                        .append("itemPrice", "$itemPrice")
                        .append("stockDetails", "$stockDetails")
                        .append("specialProduct", "$specialProduct")
                        .append("lastUpdateDate", "$lastUpdateDate")
                        .append("rating", "$rating")
                        .append("comment", "$comment")))));

        // Set final aggregation pipeline as response body
        exchange.getIn().setBody(pipeline);
    }
}
