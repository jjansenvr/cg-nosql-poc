package nl.gemeente.zaken.api;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class RelationeelZgwController {

    private final JdbcClient jdbcClient;

    public RelationeelZgwController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- A. Oude ZGW 5-endpoint stijl op genormaliseerde SQL tabellen ---

    @GetMapping("/rel/zaken/api/v1/zaken/{uuid}")
    public ResponseEntity<Map<String, Object>> getZaak(@PathVariable UUID uuid) {
        return jdbcClient.sql("SELECT * FROM rel_zaken WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query().listOfRows().stream().findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rel/zaken/api/v1/statussen")
    public List<Map<String, Object>> getStatussen(@RequestParam("zaak") String zaakUrl) {
        UUID uuid = parseUuid(zaakUrl);
        return jdbcClient.sql("SELECT s.* FROM rel_statussen s JOIN rel_zaken z ON s.zaak_id = z.id WHERE z.uuid = :uuid")
                .param("uuid", uuid).query().listOfRows();
    }

    @GetMapping("/rel/zaken/api/v1/rollen")
    public List<Map<String, Object>> getRollen(@RequestParam("zaak") String zaakUrl) {
        UUID uuid = parseUuid(zaakUrl);
        return jdbcClient.sql("SELECT r.* FROM rel_rollen r JOIN rel_zaken z ON r.zaak_id = z.id WHERE z.uuid = :uuid")
                .param("uuid", uuid).query().listOfRows();
    }

    @GetMapping("/rel/zaken/api/v1/zaakobjecten")
    public List<Map<String, Object>> getZaakobjecten(@RequestParam("zaak") String zaakUrl) {
        UUID uuid = parseUuid(zaakUrl);
        return jdbcClient.sql("SELECT e.* FROM rel_eigenschappen e JOIN rel_zaken z ON e.zaak_id = z.id WHERE z.uuid = :uuid")
                .param("uuid", uuid).query().listOfRows();
    }

    @GetMapping("/rel/zaken/api/v1/zaakinformatieobjecten")
    public List<Map<String, Object>> getDocumenten(@RequestParam("zaak") String zaakUrl) {
        UUID uuid = parseUuid(zaakUrl);
        // Simuleer index-lookup op koppeltabel zodat alle 5 calls realistisch de DB raken
        return jdbcClient.sql("SELECT id FROM rel_zaken WHERE uuid = :uuid AND 1=0")
                .param("uuid", uuid).query().listOfRows();
    }

    // --- B. Aggregaat-endpoint op relationele tabellen (1 HTTP call, meerdere SQL queries) ---

    @GetMapping("/rel/api/v1/zaken/{uuid}")
    public ResponseEntity<Map<String, Object>> getVolledigDossierRelationeel(@PathVariable UUID uuid) {
        var zaakOpt = jdbcClient.sql("SELECT * FROM rel_zaken WHERE uuid = :uuid")
                .param("uuid", uuid).query().listOfRows().stream().findFirst();

        if (zaakOpt.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> dossier = new java.util.HashMap<>(zaakOpt.get());
        Long zaakId = ((Number) dossier.get("id")).longValue();

        dossier.put("statusHistorie", jdbcClient.sql("SELECT * FROM rel_statussen WHERE zaak_id = :id").param("id", zaakId).query().listOfRows());
        dossier.put("rollen", jdbcClient.sql("SELECT * FROM rel_rollen WHERE zaak_id = :id").param("id", zaakId).query().listOfRows());
        dossier.put("eigenschappen", jdbcClient.sql("SELECT * FROM rel_eigenschappen WHERE zaak_id = :id").param("id", zaakId).query().listOfRows());

        return ResponseEntity.ok(dossier);
    }

    // --- C. Create/update, ZGW-stijl (losse resources) ---

    @PostMapping("/rel/zaken/api/v1/zaken")
    @Transactional
    public ResponseEntity<Map<String, Object>> maakZaak(@RequestBody Map<String, Object> body) {
        UUID uuid = UUID.randomUUID();
        insertZaak(uuid, body);
        return ResponseEntity.created(URI.create("/rel/zaken/api/v1/zaken/" + uuid)).body(getZaak(uuid).getBody());
    }

    @PutMapping("/rel/zaken/api/v1/zaken/{uuid}")
    public ResponseEntity<Map<String, Object>> wijzigZaak(@PathVariable UUID uuid, @RequestBody Map<String, Object> body) {
        return updateZaak(uuid, body) == 0 ? ResponseEntity.notFound().build() : getZaak(uuid);
    }

    @PostMapping("/rel/zaken/api/v1/statussen")
    public ResponseEntity<Void> maakStatus(@RequestBody Map<String, Object> body) {
        return insertStatus(parseUuid((String) body.get("zaak")), body) == 0
                ? ResponseEntity.notFound().build() : ResponseEntity.status(201).build();
    }

    @PostMapping("/rel/zaken/api/v1/rollen")
    public ResponseEntity<Void> maakRol(@RequestBody Map<String, Object> body) {
        return insertRol(parseUuid((String) body.get("zaak")), body) == 0
                ? ResponseEntity.notFound().build() : ResponseEntity.status(201).build();
    }

    // --- D. Create/update, aggregaat (1 HTTP call, 1 transactie) ---

    @PostMapping("/rel/api/v1/zaken")
    @Transactional
    public ResponseEntity<UUID> maakDossier(@RequestBody Map<String, Object> body) {
        UUID uuid = UUID.randomUUID();
        insertZaak(uuid, body);
        insertKinderen(uuid, body);
        return ResponseEntity.created(URI.create("/rel/api/v1/zaken/" + uuid)).body(uuid);
    }

    @PutMapping("/rel/api/v1/zaken/{uuid}")
    @Transactional
    public ResponseEntity<Void> wijzigDossier(@PathVariable UUID uuid, @RequestBody Map<String, Object> body) {
        if (updateZaak(uuid, body) == 0) return ResponseEntity.notFound().build();
        for (String tabel : List.of("rel_statussen", "rel_rollen", "rel_eigenschappen")) {
            jdbcClient.sql("DELETE FROM " + tabel + " WHERE zaak_id = (SELECT id FROM rel_zaken WHERE uuid = :uuid)")
                    .param("uuid", uuid).update();
        }
        insertKinderen(uuid, body);
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private void insertKinderen(UUID uuid, Map<String, Object> body) {
        for (Object s : (List<Object>) body.getOrDefault("statusHistorie", List.of())) insertStatus(uuid, (Map<String, Object>) s);
        for (Object r : (List<Object>) body.getOrDefault("rollen", List.of())) insertRol(uuid, (Map<String, Object>) r);
        Map<String, Object> eigenschappen = (Map<String, Object>) body.getOrDefault("eigenschappen", Map.of());
        eigenschappen.forEach((naam, waarde) -> jdbcClient.sql(
                "INSERT INTO rel_eigenschappen (zaak_id, naam, waarde) SELECT id, :naam, :waarde FROM rel_zaken WHERE uuid = :uuid")
                .param("uuid", uuid).param("naam", naam).param("waarde", String.valueOf(waarde)).update());
    }

    private void insertZaak(UUID uuid, Map<String, Object> body) {
        jdbcClient.sql("""
                INSERT INTO rel_zaken (uuid, identificatie, bronorganisatie, zaaktype, omschrijving, registratiedatum)
                VALUES (:uuid, :identificatie, :bronorganisatie, :zaaktype, :omschrijving, :registratiedatum)
                """)
                .param("uuid", uuid)
                .param("identificatie", body.get("identificatie"))
                .param("bronorganisatie", body.get("bronorganisatie"))
                .param("zaaktype", body.get("zaaktype"))
                .param("omschrijving", body.get("omschrijving"))
                .param("registratiedatum", datum(body.get("registratiedatum")))
                .update();
    }

    private int updateZaak(UUID uuid, Map<String, Object> body) {
        return jdbcClient.sql("""
                UPDATE rel_zaken SET
                    identificatie = COALESCE(:identificatie, identificatie),
                    bronorganisatie = COALESCE(:bronorganisatie, bronorganisatie),
                    zaaktype = COALESCE(:zaaktype, zaaktype),
                    omschrijving = COALESCE(:omschrijving, omschrijving),
                    registratiedatum = COALESCE(:registratiedatum, registratiedatum)
                WHERE uuid = :uuid
                """)
                .param("uuid", uuid)
                .param("identificatie", body.get("identificatie"))
                .param("bronorganisatie", body.get("bronorganisatie"))
                .param("zaaktype", body.get("zaaktype"))
                .param("omschrijving", body.get("omschrijving"))
                .param("registratiedatum", body.get("registratiedatum") == null ? null : datum(body.get("registratiedatum")))
                .update();
    }

    private int insertStatus(UUID uuid, Map<String, Object> s) {
        Object gezet = s.get("datumStatusGezet");
        return jdbcClient.sql("""
                INSERT INTO rel_statussen (zaak_id, datum_status_gezet, statustoelichting)
                SELECT id, :datum, :toelichting FROM rel_zaken WHERE uuid = :uuid
                """)
                .param("uuid", uuid)
                .param("datum", gezet == null ? OffsetDateTime.now() : OffsetDateTime.parse(gezet.toString()))
                .param("toelichting", s.get("statustoelichting"))
                .update();
    }

    private int insertRol(UUID uuid, Map<String, Object> r) {
        return jdbcClient.sql("""
                INSERT INTO rel_rollen (zaak_id, betrokkene_type, omschrijving_generiek, roltoelichting)
                SELECT id, :type, :generiek, :toelichting FROM rel_zaken WHERE uuid = :uuid
                """)
                .param("uuid", uuid)
                .param("type", r.get("betrokkeneType"))
                .param("generiek", r.get("omschrijvingGeneriek"))
                .param("toelichting", r.get("roltoelichting"))
                .update();
    }

    private LocalDate datum(Object waarde) {
        return waarde == null ? LocalDate.now() : LocalDate.parse(waarde.toString());
    }

    private UUID parseUuid(String url) {
        String schoon = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return UUID.fromString(schoon.substring(schoon.lastIndexOf('/') + 1));
    }
}
