package nl.gemeente.zaken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.commonground.engine.repository.TemporeelDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class ZakenNoSqlIntegratieTest {

    // Start automatisch een PostgreSQL 16 container en koppelt de datasource zonder extra configuratie
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TemporeelDocumentRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testVolledigeFlow_Validatie_Upcasting_En_Tijdreizen() throws Exception {
        String zaaktypeUrl = "https://gemeente.nl";

        // =========================================================================
        // STAP 1: Test Governance tegen wildgroei (v1.1 registreren met foutief veld)
        // =========================================================================
        String ongeldigeZaakJson = """
            {
              "identificatie": "ZAAK-2026-FOUT",
              "bronorganisatie": "123456789",
              "zaaktype": "%s",
              "registratiedatum": "2026-11-20",
              "eigenschappen": {
                "locatie": { "adres": "Kerkstraat 1, Fijnaart" },
                "kanaal": "Digitaal Loket",
                "wildgroei_veld": "Dit mag niet van het JSON Schema!"
              }
            }
            """.formatted(zaaktypeUrl);

        // Verwacht een fout (400 of 500 afhankelijk van je ExceptionHandler) omdat additionalProperties=false is
        mockMvc.perform(post("/api/v1/zaken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ongeldigeZaakJson))
                .andExpect(status().is4xxClientError());


        // =========================================================================
        // STAP 2: Test API-evolutie (Simuleer een oud v1.0 document in de database)
        // =========================================================================
        UUID historischeZaakUuid = UUID.randomUUID();
        Instant gisteren = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant vandaag = Instant.now();

        // Oud formaat: plat 'locatie_adres' veld en geen 'kanaal'
        String oudDocumentV1_0 = """
            {
              "identificatie": "ZAAK-2025-OUD",
              "bronorganisatie": "123456789",
              "zaaktype": "%s",
              "registratiedatum": "2025-05-10",
              "eigenschappen": {
                "locatie_adres": "Molenstraat 14, Fijnaart"
              }
            }
            """.formatted(zaaktypeUrl);

        JsonNode v1Node = objectMapper.readTree(oudDocumentV1_0);
        repository.opslaanNieuweVersie("zaken_historie", historischeZaakUuid, "1.0", v1Node, gisteren);

        // Haal de zaak op via de v1.1 API -> De Upcaster moet hem on-the-fly vertalen!
        mockMvc.perform(get("/api/v1/zaken/" + historischeZaakUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eigenschappen.locatie.adres").value("Molenstraat 14, Fijnaart"))
                .andExpect(jsonPath("$.eigenschappen.locatie_adres").doesNotExist())
                .andExpect(jsonPath("$.eigenschappen.kanaal").value("Archief / Onbekend"));


        // =========================================================================
        // STAP 3: Test Temporele Historie (Tijdreizen na een wijziging)
        // =========================================================================
        // Registreer een nieuwe versie (v1.1) op 'vandaag' met een gewijzigd adres
        String nieuwDocumentV1_1 = """
            {
              "identificatie": "ZAAK-2025-OUD",
              "bronorganisatie": "123456789",
              "zaaktype": "%s",
              "registratiedatum": "2025-05-10",
              "eigenschappen": {
                "locatie": { "adres": "Voorstraat 99, Fijnaart" },
                "kanaal": "Balie"
              }
            }
            """.formatted(zaaktypeUrl);

        repository.opslaanNieuweVersie("zaken_historie", historischeZaakUuid, "1.1", objectMapper.readTree(nieuwDocumentV1_1), vandaag);

        // A. Vraag de actuele stand op (vandaag) -> Moet Voorstraat 99 zijn
        mockMvc.perform(get("/api/v1/zaken/" + historischeZaakUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eigenschappen.locatie.adres").value("Voorstraat 99, Fijnaart"))
                .andExpect(jsonPath("$.eigenschappen.kanaal").value("Balie"));

        // B. Reis terug in de tijd (gisteren + 1 uur) -> Moet nog steeds Molenstraat 14 zijn (en netjes ge-upcast!)
        Instant peilMomentGisteren = gisteren.plus(1, ChronoUnit.HOURS);
        mockMvc.perform(get("/api/v1/zaken/" + historischeZaakUuid)
                .param("peildatum", peilMomentGisteren.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eigenschappen.locatie.adres").value("Molenstraat 14, Fijnaart"))
                .andExpect(jsonPath("$.eigenschappen.kanaal").value("Archief / Onbekend"));
    }
}
