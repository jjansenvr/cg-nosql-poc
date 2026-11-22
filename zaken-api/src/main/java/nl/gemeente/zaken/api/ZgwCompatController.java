package nl.gemeente.zaken.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.commonground.engine.repository.TemporeelDocumentRepository;
import nl.commonground.engine.schema.SchemaRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Backward-compatible ZGW-facade: de "chatty" ZGW-endpoints worden als projecties
 * op hetzelfde zaakdocument beantwoord.
 */
@RestController
@RequestMapping("/zaken/api/v1")
public class ZgwCompatController {

    private static final String TABEL = "zaken_historie";
    private static final String SCHEMA_VERSIE = "1.1";
    private static final List<String> KINDLIJSTEN = List.of("statusHistorie", "rollen", "zaakobjecten", "zaakinformatieobjecten");

    private final TemporeelDocumentRepository repository;
    private final SchemaRegistryService schemaValidator;
    private final ObjectMapper mapper;

    public ZgwCompatController(TemporeelDocumentRepository repository,
                               SchemaRegistryService schemaValidator,
                               ObjectMapper mapper) {
        this.repository = repository;
        this.schemaValidator = schemaValidator;
        this.mapper = mapper;
    }

    @GetMapping("/zaken/{uuid}")
    public ResponseEntity<JsonNode> zaak(@PathVariable UUID uuid) {
        return document(uuid).map(d -> ResponseEntity.<JsonNode>ok(zaakView(uuid, d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/zaken")
    public ResponseEntity<JsonNode> maakZaak(@RequestBody ObjectNode body) {
        UUID uuid = UUID.randomUUID();
        body.remove(List.of("uuid", "url"));
        schemaValidator.valideer(body.path("zaaktype").asText(), SCHEMA_VERSIE, body.get("eigenschappen"));
        opslaan(uuid, body);
        return ResponseEntity.created(URI.create("/zaken/api/v1/zaken/" + uuid)).body(zaakView(uuid, body));
    }

    @PutMapping("/zaken/{uuid}")
    public ResponseEntity<JsonNode> wijzigZaak(@PathVariable UUID uuid, @RequestBody ObjectNode body) {
        return document(uuid).map(d -> {
            body.remove(List.of("uuid", "url"));
            body.remove(KINDLIJSTEN);
            d.setAll(body);
            schemaValidator.valideer(d.path("zaaktype").asText(), SCHEMA_VERSIE, d.get("eigenschappen"));
            opslaan(uuid, d);
            return ResponseEntity.<JsonNode>ok(zaakView(uuid, d));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/statussen")
    public ResponseEntity<JsonNode> maakStatus(@RequestBody ObjectNode body) {
        return voegKindToe(body, "statusHistorie", true);
    }

    @PostMapping("/rollen")
    public ResponseEntity<JsonNode> maakRol(@RequestBody ObjectNode body) {
        return voegKindToe(body, "rollen", false);
    }

    @GetMapping("/statussen")
    public ResponseEntity<JsonNode> statussen(@RequestParam String zaak) {
        UUID uuid = uuidUitUrl(zaak);
        var historie = project(uuid, "statusHistorie");
        if (historie.isEmpty()) return ResponseEntity.notFound().build();
        if (!SCHEMA_VERSIE.equals(historie.get().schemaVersie())) return lijst(zaak, ZgwCompatController::statussenUitDocument);
        if (historie.get().waarde().isArray()) return ResponseEntity.ok(pagina(historie.get().waarde()));
        var actueel = project(uuid, "actueleStatus");
        ArrayNode result = mapper.createArrayNode();
        actueel.map(a -> a.waarde()).filter(w -> !w.isNull()).ifPresent(result::add);
        return ResponseEntity.ok(pagina(result));
    }

    @GetMapping("/rollen")
    public ResponseEntity<JsonNode> rollen(@RequestParam String zaak) {
        return lijstProjectie(zaak, "rollen");
    }

    @GetMapping("/zaakobjecten")
    public ResponseEntity<JsonNode> zaakobjecten(@RequestParam String zaak) {
        return lijstProjectie(zaak, "zaakobjecten");
    }

    @GetMapping("/zaakinformatieobjecten")
    public ResponseEntity<JsonNode> zaakinformatieobjecten(@RequestParam String zaak) {
        return lijstProjectie(zaak, "zaakinformatieobjecten");
    }

    private java.util.Optional<TemporeelDocumentRepository.Projectie> project(UUID uuid, String veld) {
        return repository.zoekProjectieOpPeildatum(TABEL, uuid, Instant.now(), veld);
    }

    private ResponseEntity<JsonNode> lijstProjectie(String zaakUrl, String veld) {
        UUID uuid = uuidUitUrl(zaakUrl);
        var p = project(uuid, veld);
        if (p.isEmpty()) return ResponseEntity.notFound().build();
        // Oudere schemaversies hebben upcasting nodig, dan via het volledige document
        if (!SCHEMA_VERSIE.equals(p.get().schemaVersie())) return lijst(zaakUrl, d -> d.path(veld));
        return ResponseEntity.ok(pagina(p.get().waarde()));
    }

    private static JsonNode statussenUitDocument(ObjectNode d) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (d.has("statusHistorie")) result.addAll((ArrayNode) d.get("statusHistorie"));
        else if (d.has("actueleStatus")) result.add(d.get("actueleStatus"));
        return result;
    }

    private JsonNode pagina(JsonNode slice) {
        ArrayNode results = mapper.createArrayNode();
        if (slice.isArray()) results.addAll((ArrayNode) slice);
        ObjectNode page = mapper.createObjectNode();
        page.put("count", results.size());
        page.putNull("next");
        page.putNull("previous");
        page.set("results", results);
        return page;
    }

    private ResponseEntity<JsonNode> voegKindToe(ObjectNode body, String lijstNaam, boolean isStatus) {
        UUID uuid = uuidUitUrl(body.path("zaak").asText());
        return document(uuid).map(d -> {
            body.remove("zaak");
            if (!d.has(lijstNaam) || !d.get(lijstNaam).isArray()) d.set(lijstNaam, mapper.createArrayNode());
            ((ArrayNode) d.get(lijstNaam)).add(body);
            if (isStatus) d.set("actueleStatus", body);
            opslaan(uuid, d);
            return ResponseEntity.status(201).<JsonNode>body(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    private ObjectNode zaakView(UUID uuid, ObjectNode d) {
        ObjectNode zaak = d.deepCopy();
        zaak.put("uuid", uuid.toString());
        zaak.put("url", "/zaken/api/v1/zaken/" + uuid);
        zaak.remove(KINDLIJSTEN);
        return zaak;
    }

    private void opslaan(UUID uuid, ObjectNode d) {
        repository.opslaanNieuweVersie(TABEL, uuid, SCHEMA_VERSIE, d, Instant.now());
    }

    private UUID uuidUitUrl(String url) {
        return UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
    }

    private ResponseEntity<JsonNode> lijst(String zaakUrl, java.util.function.Function<ObjectNode, JsonNode> projectie) {
        UUID uuid = uuidUitUrl(zaakUrl);
        return document(uuid).map(d -> ResponseEntity.<JsonNode>ok(pagina(projectie.apply(d))))
                .orElse(ResponseEntity.notFound().build());
    }

    private java.util.Optional<ObjectNode> document(UUID uuid) {
        return repository.zoekOpPeildatum(TABEL, uuid, Instant.now())
                .map(e -> (ObjectNode) schemaValidator.upcastIndienNodig(e));
    }
}
