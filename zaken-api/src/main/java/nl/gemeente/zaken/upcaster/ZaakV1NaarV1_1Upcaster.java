package nl.gemeente.zaken.upcaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.commonground.engine.schema.DocumentUpcaster;
import org.springframework.stereotype.Component;

@Component
public class ZaakV1NaarV1_1Upcaster implements DocumentUpcaster {

    private final ObjectMapper objectMapper;

    public ZaakV1NaarV1_1Upcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String bronVersie() {
        return "1.0";
    }

    @Override
    public String doelVersie() {
        return "1.1";
    }

    @Override
    public JsonNode upcast(JsonNode oudDocument) {
        // Maak een kopie zodat we de originele node niet muteren
        ObjectNode nieuwDocument = oudDocument.deepCopy();

        if (nieuwDocument.has("eigenschappen") && nieuwDocument.get("eigenschappen").isObject()) {
            ObjectNode eigenschappen = (ObjectNode) nieuwDocument.get("eigenschappen");

            // 1. Transformeer oud plat veld 'locatie_adres' naar genest object 'locatie.adres'
            if (eigenschappen.has("locatie_adres")) {
                String oudAdres = eigenschappen.get("locatie_adres").asText();
                ObjectNode locatieNode = objectMapper.createObjectNode();
                locatieNode.put("adres", oudAdres);

                eigenschappen.set("locatie", locatieNode);
                eigenschappen.remove("locatie_adres");
            }

            // 2. Voeg nieuw veld 'kanaal' toe met een veilige fallback voor oude zaken
            if (!eigenschappen.has("kanaal")) {
                eigenschappen.put("kanaal", "Archief / Onbekend");
            }
        }

        return nieuwDocument;
    }
}
