package nl.gemeente.zaken.benchmark;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("benchmark")
public class BenchmarkSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public BenchmarkSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Integer aantal = jdbcTemplate.queryForObject("SELECT count(*) FROM zaken_historie", Integer.class);
        if (aantal != null && aantal >= 10_000) return;

        System.out.println("Start vullen van 10.000 NoSQL Zaken voor benchmark...");
        String sql = """
            INSERT INTO zaken_historie (entiteit_uuid, schema_versie, geldig_vanaf, data)
            SELECT 
                gen_random_uuid(),
                '1.1',
                now(),
                jsonb_build_object(
                    'identificatie', 'ZAAK-BENCH-' || i,
                    'bronorganisatie', '123456789',
                    'zaaktype', 'https://gemeente.nl',
                    'registratiedatum', '2026-11-20',
                    'actueleStatus', jsonb_build_object('statustoelichting', 'In behandeling'),
                    'rollen', jsonb_build_array(jsonb_build_object('roltoelichting', 'Aanvrager')),
                    'eigenschappen', jsonb_build_object(
                        'locatie', jsonb_build_object('adres', 'Kerkstraat ' || (i % 500) || ', Fijnaart'),
                        'kanaal', CASE WHEN i % 2 = 0 THEN 'Digitaal Loket' ELSE 'Balie' END,
                        'spoedaanvraag', (i % 10 = 0)
                    )
                )
            FROM generate_series(1, 10000) AS s(i);
            """;
        jdbcTemplate.execute(sql);
        System.out.println("Klaar! 10.000 zaken geladen.");
    }
}
