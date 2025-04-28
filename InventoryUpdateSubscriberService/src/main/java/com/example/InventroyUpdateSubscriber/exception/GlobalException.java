package com.example.InventroyUpdateSubscriber.exception;

import com.mongodb.MongoClientException;
import org.apache.camel.builder.RouteBuilder;

public class GlobalException extends RouteBuilder {
    @Override
    public void configure() {


        onException(MongoClientException.class)
                .handled(true)
                .log("MongoDB exception occurred: ${exception.message}")
                .bean("inventoryUpdates", "handleMongoDbException")
                .to("log:MongoException")
                .end();
    }
}
