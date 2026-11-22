-- 1. Genormaliseerde tabellen (conform ZGW / Open Zaak)
CREATE TABLE IF NOT EXISTS rel_zaken (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID UNIQUE NOT NULL,
    identificatie VARCHAR(40) NOT NULL,
    bronorganisatie VARCHAR(9) NOT NULL,
    zaaktype VARCHAR(255) NOT NULL,
    omschrijving TEXT,
    registratiedatum DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS rel_statussen (
    id BIGSERIAL PRIMARY KEY,
    zaak_id BIGINT NOT NULL REFERENCES rel_zaken(id),
    datum_status_gezet TIMESTAMPTZ NOT NULL,
    statustoelichting TEXT
);
CREATE INDEX IF NOT EXISTS idx_rel_statussen_zaak ON rel_statussen(zaak_id);

CREATE TABLE IF NOT EXISTS rel_rollen (
    id BIGSERIAL PRIMARY KEY,
    zaak_id BIGINT NOT NULL REFERENCES rel_zaken(id),
    betrokkene_type VARCHAR(50) NOT NULL,
    omschrijving_generiek VARCHAR(50),
    roltoelichting TEXT
);
CREATE INDEX IF NOT EXISTS idx_rel_rollen_zaak ON rel_rollen(zaak_id);

CREATE TABLE IF NOT EXISTS rel_eigenschappen (
    id BIGSERIAL PRIMARY KEY,
    zaak_id BIGINT NOT NULL REFERENCES rel_zaken(id),
    naam VARCHAR(100) NOT NULL,
    waarde TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rel_eigenschappen_zaak ON rel_eigenschappen(zaak_id);

-- 2. Vul met exact dezelfde 10.000 zaken (...0001 t/m ...10000)
TRUNCATE rel_eigenschappen, rel_rollen, rel_statussen, rel_zaken RESTART IDENTITY;

INSERT INTO rel_zaken (uuid, identificatie, bronorganisatie, zaaktype, omschrijving, registratiedatum)
SELECT 
    ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
    'ZAAK-BENCH-' || i,
    '000000000',
    'https://gemeente.nl',
    'Aanvraag omgevingsvergunning #' || i,
    CURRENT_DATE
FROM generate_series(1, 10000) AS s(i);

INSERT INTO rel_statussen (zaak_id, datum_status_gezet, statustoelichting)
SELECT id, now(), 'In behandeling' FROM rel_zaken;

INSERT INTO rel_rollen (zaak_id, betrokkene_type, omschrijving_generiek, roltoelichting)
SELECT id, 'natuurlijk_persoon', 'initiator', 'Aanvrager' FROM rel_zaken;

INSERT INTO rel_eigenschappen (zaak_id, naam, waarde)
SELECT id, 'locatie_adres', 'Kerkstraat ' || (id % 500) || ', Fijnaart' FROM rel_zaken
UNION ALL
SELECT id, 'kanaal', CASE WHEN id % 2 = 0 THEN 'Digitaal Loket' ELSE 'Balie' END FROM rel_zaken;
