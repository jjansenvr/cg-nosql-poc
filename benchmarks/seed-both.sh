#!/usr/bin/env bash
set -e

echo "1. Aanmaken autorisaties & 1 sjabloon-zaak in Open Zaak..."
TOKEN=$(docker compose exec -T openzaak src/manage.py shell -c "
import jwt, time
from datetime import date, timedelta
from django.utils import timezone
from vng_api_common.contrib.setup_configuration.steps import (
    JWTSecretsConfigurationStep, JWTSecretsConfigurationModel,
    ApplicatieConfigurationStep, ApplicatieConfigurationModel
)
from openzaak.components.catalogi.models import Catalogus, ZaakType, StatusType, RolType
from openzaak.components.zaken.models import Zaak, Status, Rol

secret = 'benchmark-geheime-sleutel-van-32-tekens!'

JWTSecretsConfigurationStep().execute(JWTSecretsConfigurationModel(items=[
    {'identifier': 'benchmark-client', 'secret': secret}
]))
ApplicatieConfigurationStep().execute(ApplicatieConfigurationModel(items=[
    {
        'uuid': '99999999-9999-9999-9999-999999999999',
        'label': 'K6 Benchmark Client',
        'client_ids': ['benchmark-client'],
        'heeft_alle_autorisaties': True
    }
]))

cat, _ = Catalogus.objects.get_or_create(
    domein='BENCH',
    defaults={
        'rsin': '000000000',
        'contactpersoon_beheer_naam': 'Benchmark Beheer',
        'contactpersoon_beheer_telefoonnummer': '0612345678',
        'contactpersoon_beheer_emailadres': 'beheer@gemeente.nl',
    }
)

zt, _ = ZaakType.objects.get_or_create(
    identificatie='ZT-BENCH-01',
    catalogus=cat,
    defaults={
        'concept': False,
        'datum_begin_geldigheid': date(2020, 1, 1),
        'versiedatum': date(2020, 1, 1),
        'doorlooptijd_behandeling': timedelta(days=30),
        'servicenorm_behandeling': timedelta(days=14),
        'opschorting_en_aanhouding_mogelijk': False,
        'verlenging_mogelijk': False,
        'publicatie_indicatie': False,
    }
)

st, _ = StatusType.objects.get_or_create(
    zaaktype=zt,
    statustypevolgnummer=1,
    defaults={
        'statustype_omschrijving': 'In behandeling',
        'datum_begin_geldigheid': date(2020, 1, 1),
    }
)

rt_fields = {f.name for f in RolType._meta.fields}
rt_defaults = {'omschrijving_generiek': 'initiator'}
if 'datum_begin_geldigheid' in rt_fields:
    rt_defaults['datum_begin_geldigheid'] = date(2020, 1, 1)
if 'omschrijving' in rt_fields:
    rt_defaults['omschrijving'] = 'Aanvrager'
elif 'roltype_omschrijving' in rt_fields:
    rt_defaults['roltype_omschrijving'] = 'Aanvrager'

rt, _ = RolType.objects.get_or_create(zaaktype=zt, defaults=rt_defaults)

if not Zaak.objects.exists():
    zaak = Zaak.objects.create(
        identificatie='ZAAK-BENCH-1',
        bronorganisatie='000000000',
        zaaktype=zt,
        registratiedatum=date.today(),
        startdatum=date.today(),
        verantwoordelijke_organisatie='000000000',
    )
    Status.objects.create(zaak=zaak, statustype=st, datum_status_gezet=timezone.now(), statustoelichting='In behandeling')
    Rol.objects.create(zaak=zaak, betrokkene_type='natuurlijk_persoon', roltype=rt, omschrijving_generiek='initiator')

payload = {'iss': 'benchmark-client', 'iat': int(time.time()), 'exp': int(time.time()) + 86400, 'client_id': 'benchmark-client', 'user_id': 'bench', 'user_representation': 'Bench'}
print(jwt.encode(payload, secret, algorithm='HS256'))
" | tr -d '\r' | tail -n 1)

echo "2. Klonen naar 10.000 identieke zaken in Open Zaak database..."
docker compose exec -T postgres-openzaak psql -U openzaak -d openzaak -q << 'EOF'
BEGIN;

DELETE FROM zaken_rol WHERE zaak_id NOT IN (SELECT min(id) FROM zaken_zaak);
DELETE FROM zaken_status WHERE zaak_id NOT IN (SELECT min(id) FROM zaken_zaak);
DELETE FROM zaken_zaak WHERE id NOT IN (SELECT min(id) FROM zaken_zaak);

UPDATE zaken_zaak 
SET uuid = '00000000-0000-4000-8000-000000000001'::uuid,
    identificatie = 'ZAAK-BENCH-1';

DO $$
DECLARE
    cols text;
    select_exprs text;
BEGIN
    SELECT string_agg(quote_ident(column_name), ', '),
           string_agg(
               CASE column_name
                   WHEN 'uuid' THEN '(''00000000-0000-4000-8000-'' || lpad(s.i::text, 12, ''0''))::uuid'
                   WHEN 'identificatie' THEN '''ZAAK-BENCH-'' || s.i'
                   ELSE 't.' || quote_ident(column_name)
               END, ', '
           )
    INTO cols, select_exprs
    FROM information_schema.columns
    WHERE table_name = 'zaken_zaak' AND column_name <> 'id';

    EXECUTE format(
        'INSERT INTO zaken_zaak (%s) SELECT %s FROM zaken_zaak t CROSS JOIN generate_series(2, 10000) AS s(i) WHERE t.identificatie = ''ZAAK-BENCH-1''',
        cols, select_exprs
    );

    SELECT string_agg(quote_ident(column_name), ', '),
           string_agg(
               CASE column_name
                   WHEN 'uuid' THEN 'gen_random_uuid()'
                   WHEN 'zaak_id' THEN 'z.id'
                   ELSE 't.' || quote_ident(column_name)
               END, ', '
           )
    INTO cols, select_exprs
    FROM information_schema.columns
    WHERE table_name = 'zaken_status' AND column_name <> 'id';

    EXECUTE format(
        'INSERT INTO zaken_status (%s) SELECT %s FROM zaken_zaak z CROSS JOIN (SELECT * FROM zaken_status LIMIT 1) t WHERE z.identificatie <> ''ZAAK-BENCH-1''',
        cols, select_exprs
    );

    SELECT string_agg(quote_ident(column_name), ', '),
           string_agg(
               CASE column_name
                   WHEN 'uuid' THEN 'gen_random_uuid()'
                   WHEN 'zaak_id' THEN 'z.id'
                   ELSE 't.' || quote_ident(column_name)
               END, ', '
           )
    INTO cols, select_exprs
    FROM information_schema.columns
    WHERE table_name = 'zaken_rol' AND column_name <> 'id';

    EXECUTE format(
        'INSERT INTO zaken_rol (%s) SELECT %s FROM zaken_zaak z CROSS JOIN (SELECT * FROM zaken_rol LIMIT 1) t WHERE z.identificatie <> ''ZAAK-BENCH-1''',
        cols, select_exprs
    );
END $$;

COMMIT;
EOF

echo "3. Laden van exact dezelfde 10.000 zaken in jouw Java NoSQL database..."
docker compose exec -T postgres-nosql psql -U postgres -d commonground_poc -q << 'EOF'
CREATE TABLE IF NOT EXISTS zaken_historie (
    versie_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entiteit_uuid UUID NOT NULL,
    schema_versie VARCHAR(10) NOT NULL,
    registratie_tijdstip TIMESTAMPTZ NOT NULL DEFAULT now(),
    geldig_vanaf TIMESTAMPTZ NOT NULL,
    geldig_tot TIMESTAMPTZ,
    data JSONB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_zaken_actueel ON zaken_historie (entiteit_uuid) WHERE geldig_tot IS NULL;
CREATE INDEX IF NOT EXISTS idx_zaken_gin ON zaken_historie USING GIN (data);

TRUNCATE TABLE zaken_historie;

INSERT INTO zaken_historie (entiteit_uuid, schema_versie, geldig_vanaf, data)
SELECT 
    ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
    '1.1',
    now(),
    jsonb_build_object(
        'identificatie', 'ZAAK-BENCH-' || i,
        'bronorganisatie', '000000000',
        'zaaktype', 'https://gemeente.nl',
        'omschrijving', 'Aanvraag omgevingsvergunning #' || i,
        'registratiedatum', CURRENT_DATE::text,
        'actueleStatus', jsonb_build_object('datumStatusGezet', now()::text, 'statustoelichting', 'In behandeling'),
        'statusHistorie', jsonb_build_array(jsonb_build_object('datumStatusGezet', now()::text, 'statustoelichting', 'In behandeling')),
        'rollen', jsonb_build_array(jsonb_build_object('betrokkeneType', 'natuurlijk_persoon', 'omschrijvingGeneriek', 'initiator', 'roltoelichting', 'Aanvrager')),
        'eigenschappen', jsonb_build_object(
            'locatie', jsonb_build_object('adres', 'Kerkstraat ' || (i % 500) || ', Fijnaart'),
            'kanaal', CASE WHEN i % 2 = 0 THEN 'Digitaal Loket' ELSE 'Balie' END
        )
    )
FROM generate_series(1, 10000) AS s(i);
EOF

echo ""
echo "=================================================================="
echo "KLAAR! Beide databases bevatten nu exact dezelfde 10.000 zaken."
echo "=================================================================="
echo ""
echo "1. Run Open Zaak benchmark:"
echo "k6 run -e MODE=zgw -e BASE_URL=http://localhost:8000/zaken/api/v1 -e ZAAK_UUID=00000000-0000-4000-8000-000000000042 -e AUTH_TOKEN=${TOKEN} benchmark.js"
echo ""
echo "2. Run jouw Java NoSQL benchmark:"
echo "k6 run -e MODE=nosql -e BASE_URL=http://localhost:8080/api/v1 -e ZAAK_UUID=00000000-0000-4000-8000-000000000042 benchmark.js"
echo ""