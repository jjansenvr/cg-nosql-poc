package nl.commonground.engine.schema;

import com.fasterxml.jackson.databind.JsonNode;

public interface DocumentUpcaster {
    String bronVersie();
    String doelVersie();
    JsonNode upcast(JsonNode oudDocument);
}
