package nl.commonground.engine.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import nl.commonground.engine.model.HistorieEnvelop;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SchemaRegistryService {

    private final JdbcClient jdbcClient;
    private final JsonSchemaFactory schemaFactory;
    private final Map<String, DocumentUpcaster> upcasters;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public SchemaRegistryService(JdbcClient jdbcClient, List<DocumentUpcaster> upcasterList) {
        this.jdbcClient = jdbcClient;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        // Indexeer alle beschikbare upcasters op hun bronVersie (bijv. "1.0" -> UpcasterNaar1_1)
        this.upcasters = upcasterList.stream()
                .collect(Collectors.toMap(DocumentUpcaster::bronVersie, u -> u));
    }

    public void valideer(String domeinTypeUrl, String versie, JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("Eigenschappen payload mag niet leeg zijn");
        }

        JsonSchema schema = haalSchemaOp(domeinTypeUrl, versie);
        Set<ValidationMessage> fouten = schema.validate(payload);

        if (!fouten.isEmpty()) {
            String melding = fouten.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining(", "));
            throw new SchemaValidatieException("JSON Schema validatie gefaald: " + melding);
        }
    }

    public JsonNode upcastIndienNodig(HistorieEnvelop envelop) {
        JsonNode huidigDocument = envelop.data();
        String huidigeVersie = envelop.schemaVersie();

        // Blijf door-upcasten zolang er een opvolgende versie beschikbaar is (bijv. 1.0 -> 1.1 -> 1.2)
        while (upcasters.containsKey(huidigeVersie)) {
            DocumentUpcaster upcaster = upcasters.get(huidigeVersie);
            huidigDocument = upcaster.upcast(huidigDocument);
            huidigeVersie = upcaster.doelVersie();
        }

        return huidigDocument;
    }

    private JsonSchema haalSchemaOp(String domeinTypeUrl, String versie) {
        String cacheSleutel = domeinTypeUrl + "::" + versie;
        return schemaCache.computeIfAbsent(cacheSleutel, k -> {
            String schemaJson = jdbcClient.sql("""
                    SELECT json_schema::text 
                    FROM schema_registry 
                    WHERE domein_type_url = :url AND schema_versie = :versie
                    """)
                    .param("url", domeinTypeUrl)
                    .param("versie", versie)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("Geen JSON schema gevonden voor: " + cacheSleutel));

            return schemaFactory.getSchema(schemaJson);
        });
    }

    public static class SchemaValidatieException extends RuntimeException {
        public SchemaValidatieException(String message) {
            super(message);
        }
    }
}
