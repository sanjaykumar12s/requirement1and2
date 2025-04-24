package com.example.ConsumerMicroservice.processor;

public class ProcessException extends RuntimeException {

    public ProcessException(String message)
    {
        super(message);
    }
}
