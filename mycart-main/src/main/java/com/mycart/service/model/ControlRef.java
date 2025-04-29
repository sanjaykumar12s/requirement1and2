package com.mycart.service.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "controlRef")
public class ControlRef {

    @Id
    private String id;
    private String lastProcessTs; // timestamp for the last processed record

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLastProcessTs() {
        return lastProcessTs;
    }

    public void setLastProcessTs(String lastProcessTs) {
        this.lastProcessTs = lastProcessTs;
    }
}