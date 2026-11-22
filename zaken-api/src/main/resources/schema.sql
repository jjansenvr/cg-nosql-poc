-- 1. De generieke schema registry tabel (voor governance tegen wildgroei)
CREATE TABLE IF NOT EXISTS schema_registry (
    domein_type_url VARCHAR(255) NOT NULL,
    schema_versie VARCHAR(10) NOT NULL,
    json_schema JSONB NOT NULL,
    geregistreerd_op TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (domein_type_url, schema_versie)
);

-- 2. De temporele tabel voor het Zaken-register
CREATE TABLE IF NOT EXISTS zaken_historie (
    versie_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entiteit_uuid UUID NOT NULL,
    schema_versie VARCHAR(10) NOT NULL,
    registratie_tijdstip TIMESTAMPTZ NOT NULL DEFAULT now(),
    geldig_vanaf TIMESTAMPTZ NOT NULL,
    geldig_tot TIMESTAMPTZ,
    data JSONB NOT NULL
);

-- Partial index voor razendsnelle queries op de actuele stand
CREATE INDEX IF NOT EXISTS idx_zaken_actueel ON zaken_historie (entiteit_uuid) WHERE geldig_tot IS NULL;

-- GIN index om razendsnel te kunnen zoeken binnen de flexibele JSONB eigenschappen
CREATE INDEX IF NOT EXISTS idx_zaken_gin ON zaken_historie USING GIN (data);

-- 3. Voorbeelddata: Registreer een JSON Schema v1.1 voor een Omgevingsvergunning
INSERT INTO schema_registry (domein_type_url, schema_versie, json_schema)
VALUES (
    'https://gemeente.nl',
    '1.1',
    '{
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["locatie", "kanaal"],
      "additionalProperties": false,
      "properties": {
        "locatie": {
          "type": "object",
          "required": ["adres"],
          "properties": {
            "adres": { "type": "string", "minLength": 3 }
          }
        },
        "kanaal": {
          "type": "string",
          "enum": ["Digitaal Loket", "Balie", "Post", "Archief / Onbekend"]
        },
        "spoedaanvraag": {
          "type": "boolean"
        }
      }
    }'::jsonb
) ON CONFLICT DO NOTHING;
