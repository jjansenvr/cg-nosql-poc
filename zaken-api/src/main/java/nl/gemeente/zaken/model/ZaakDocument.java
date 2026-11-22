package nl.gemeente.zaken.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record ZaakDocument(
    @NotBlank String identificatie,
    @NotBlank String bronorganisatie,
    @NotBlank String zaaktype,
    @NotNull LocalDate registratiedatum,
    JsonNode actueleStatus,
    List<JsonNode> statusHistorie,
    List<JsonNode> rollen,
    JsonNode eigenschappen // <-- Dit deel wordt gevalideerd door het JSON Schema!
) {}
