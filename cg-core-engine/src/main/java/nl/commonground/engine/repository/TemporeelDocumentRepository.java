package nl.commonground.engine.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.commonground.engine.model.HistorieEnvelop;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TemporeelDocumentRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public TemporeelDocumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void opslaanNieuweVersie(String tabelNaam, UUID entiteitUuid, String schemaVersie, JsonNode data, Instant geldigVanaf) {
        // 1. Sluit de huidige actuele versie af (materiële historie)
        jdbcClient.sql("UPDATE " + tabelNaam + " SET geldig_tot = :geldigVanaf WHERE entiteit_uuid = :uuid AND geldig_tot IS NULL")
                .param("geldigVanaf", Timestamp.from(geldigVanaf))
                .param("uuid", entiteitUuid)
                .update();

        // 2. Voeg de nieuwe versie toe als append-only snapshot
        jdbcClient.sql("""
                INSERT INTO %s (entiteit_uuid, schema_versie, geldig_vanaf, data)
                VALUES (:uuid, :versie, :geldigVanaf, :data::jsonb)
                """.formatted(tabelNaam))
                .param("uuid", entiteitUuid)
                .param("versie", schemaVersie)
                .param("geldigVanaf", Timestamp.from(geldigVanaf))
                .param("data", data.toString())
                .update();
    }

    public record Projectie(String schemaVersie, JsonNode waarde) {}

    /** Haalt één JSONB-veld (data->'veld') uit de database, zonder het hele document te transporteren of te parsen. */
    public Optional<Projectie> zoekProjectieOpPeildatum(String tabelNaam, UUID entiteitUuid, Instant peildatum, String veld) {
        return jdbcClient.sql("""
                SELECT schema_versie, (data -> :veld)::text AS waarde
                FROM %s
                WHERE entiteit_uuid = :uuid
                  AND geldig_vanaf <= :peil
                  AND (geldig_tot > :peil OR geldig_tot IS NULL)
                """.formatted(tabelNaam))
                .param("veld", veld)
                .param("uuid", entiteitUuid)
                .param("peil", Timestamp.from(peildatum))
                .query((rs, rowNum) -> {
                    try {
                        String waarde = rs.getString("waarde");
                        return new Projectie(rs.getString("schema_versie"),
                                waarde == null ? objectMapper.nullNode() : objectMapper.readTree(waarde));
                    } catch (Exception e) {
                        throw new RuntimeException("Fout bij parsen JSONB-projectie uit database", e);
                    }
                })
                .optional();
    }

    public Optional<HistorieEnvelop> zoekOpPeildatum(String tabelNaam, UUID entiteitUuid, Instant peildatum) {
        return jdbcClient.sql("""
                SELECT versie_id, entiteit_uuid, schema_versie, registratie_tijdstip, geldig_vanaf, geldig_tot, data::text
                FROM %s
                WHERE entiteit_uuid = :uuid
                  AND geldig_vanaf <= :peil
                  AND (geldig_tot > :peil OR geldig_tot IS NULL)
                """.formatted(tabelNaam))
                .param("uuid", entiteitUuid)
                .param("peil", Timestamp.from(peildatum))
                .query((rs, rowNum) -> {
                    try {
                        return new HistorieEnvelop(
                            UUID.fromString(rs.getString("versie_id")),
                            UUID.fromString(rs.getString("entiteit_uuid")),
                            rs.getString("schema_versie"),
                            rs.getTimestamp("registratie_tijdstip").toInstant(),
                            rs.getTimestamp("geldig_vanaf").toInstant(),
                            rs.getTimestamp("geldig_tot") != null ? rs.getTimestamp("geldig_tot").toInstant() : null,
                            objectMapper.readTree(rs.getString("data"))
                        );
                    } catch (Exception e) {
                        throw new RuntimeException("Fout bij parsen JSONB uit database", e);
                    }
                })
                .optional();
    }
}
