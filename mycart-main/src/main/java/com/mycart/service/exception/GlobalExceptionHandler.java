package com.mycart.service.exception;

import com.mongodb.MongoException;
import com.mycart.service.dto.Response;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class GlobalExceptionHandler extends RouteBuilder {
    @Override
    public void configure() {
//        onException(Exception.class)
//                .handled(true)
//                .log("Exception: ${exception.message}")
//                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
//                .setBody(simple("Internal server error: ${exception.message}"));


        onException(MongoException.class)
                .handled(true)
                .log(LoggingLevel.ERROR, "MongoException occurred: ${exception.message}")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .process(exchange -> {
                    Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);

                    Response errorResponse = new Response();
                    errorResponse.setError(true);
                    errorResponse.setErrorResponse("MongoDB Operation Failed");
                    errorResponse.setErrMsg(exception.getMessage());

                    exchange.getIn().setBody(errorResponse);
                });


    }
}
