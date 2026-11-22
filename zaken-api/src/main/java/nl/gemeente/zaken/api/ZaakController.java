package nl.gemeente.zaken.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import nl.commonground.engine.repository.TemporeelDocumentRepository;
import nl.commonground.engine.schema.SchemaRegistryService;
import nl.gemeente.zaken.model.ZaakDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/zaken")
public class ZaakController {

    private static final String TABEL = "zaken_historie";
    private static final String HUIDIGE_API_VERSIE = "1.1";

    private final TemporeelDocumentRepository repository;
    private final SchemaRegistryService schemaValidator;
    private final ObjectMapper objectMapper;

    public ZaakController(TemporeelDocumentRepository repository,
                          SchemaRegistryService schemaValidator,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<UUID> registreerZaak(@Valid @RequestBody ZaakDocument zaak) {
        // 1. Valideer het flexibele 'eigenschappen' blok tegen het JSON Schema van dit zaaktype
        schemaValidator.valideer(zaak.zaaktype(), HUIDIGE_API_VERSIE, zaak.eigenschappen());

        // 2. Sla op als nieuw temporeel document
        UUID nieuweZaakUuid = UUID.randomUUID();
        JsonNode documentNode = objectMapper.valueToTree(zaak);
        repository.opslaanNieuweVersie(TABEL, nieuweZaakUuid, HUIDIGE_API_VERSIE, documentNode, Instant.now());

        return ResponseEntity.ok(nieuweZaakUuid);
    }

    @PutMapping("/{uuid}")
    public ResponseEntity<Void> wijzigZaak(@PathVariable UUID uuid, @Valid @RequestBody ZaakDocument zaak) {
        if (repository.zoekOpPeildatum(TABEL, uuid, Instant.now()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        schemaValidator.valideer(zaak.zaaktype(), HUIDIGE_API_VERSIE, zaak.eigenschappen());
        JsonNode documentNode = objectMapper.valueToTree(zaak);
        repository.opslaanNieuweVersie(TABEL, uuid, HUIDIGE_API_VERSIE, documentNode, Instant.now());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<JsonNode> haalZaakOp(
            @PathVariable UUID uuid,
            @RequestParam(required = false) Instant peildatum) {
        
        Instant zoekMoment = (peildatum != null) ? peildatum : Instant.now();

        return repository.zoekOpPeildatum(TABEL, uuid, zoekMoment)
                .map(envelop -> {
                    // Hier roep je de upcaster aan als envelop.schemaVersie() < HUIDIGE_API_VERSIE
                    JsonNode geupcastDocument = schemaValidator.upcastIndienNodig(envelop);
                    return ResponseEntity.ok(geupcastDocument);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
