package com.intentreactor.api;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles LLM responses where a field is sometimes a String, sometimes a JSON array.
 */
public class StringOrArrayDeserializer extends StdDeserializer<String> {

    public StringOrArrayDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<String> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                items.add(p.getValueAsString());
            }
            return String.join(", ", items);
        }
        return p.getValueAsString();
    }
}
