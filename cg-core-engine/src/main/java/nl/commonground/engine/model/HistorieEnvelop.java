package nl.commonground.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record HistorieEnvelop(
    UUID versieId,
    UUID entiteitUuid,
    String schemaVersie,
    Instant registratieTijdstip,
    Instant geldigVanaf,
    Instant geldigTot, // null = actuele stand
    JsonNode data
) {}
