package com.mycart.service.processors;

import com.mycart.service.exception.ProcessException;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

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
}
