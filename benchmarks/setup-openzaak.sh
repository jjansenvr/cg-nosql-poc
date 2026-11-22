#!/usr/bin/env bash
set -e

echo "1. Aanmaken Autorisaties, Catalogus, Zaak & JWT-token in Open Zaak..."

OUTPUT=$(docker compose exec -T openzaak src/manage.py shell -c "
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

# 1. Autorisaties & JWT Secret
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

# 2. Catalogus & Zaaktype
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
    volgnummer=1,
    defaults={'is_eindstatus': False}
)

rt, _ = RolType.objects.get_or_create(
    zaaktype=zt,
    omschrijving_generiek='initiator'
)

# 3. Zaak met Status en Rol
zaak = Zaak.objects.create(
    identificatie=f'ZAAK-OZ-{int(time.time())}',
    bronorganisatie='000000000',
    zaaktype=zt,
    registratiedatum=date.today(),
    verantwoordelijke_organisatie='000000000',
)

Status.objects.create(
    zaak=zaak,
    statustype=st,
    datum_status_gezet=timezone.now(),
    statustoelichting='In behandeling'
)

Rol.objects.create(
    zaak=zaak,
    betrokkene_type='natuurlijk_persoon',
    roltype=rt,
    omschrijving_generiek='initiator'
)

# 4. JWT Token
payload = {
    'iss': 'benchmark-client',
    'iat': int(time.time()),
    'exp': int(time.time()) + 86400,
    'client_id': 'benchmark-client',
    'user_id': 'benchmark-user',
    'user_representation': 'Benchmark Tester'
}
token = jwt.encode(payload, secret, algorithm='HS256')

print(f'RESULT|{zaak.uuid}|{token}')
" | tr -d '\r' | grep '^RESULT|')

ZAAK_UUID=$(echo "$OUTPUT" | cut -d'|' -f2)
TOKEN=$(echo "$OUTPUT" | cut -d'|' -f3)

if [ -z "$ZAAK_UUID" ]; then
  echo "Fout bij aanmaken testdata."
  exit 1
fi

echo ""
echo "=================================================================="
echo "KLAAR! Gebruik onderstaand commando voor je Open Zaak benchmark:"
echo "=================================================================="
echo ""
echo "k6 run -e MODE=zgw -e BASE_URL=http://localhost:8000/zaken/api/v1 -e ZAAK_UUID=${ZAAK_UUID} -e AUTH_TOKEN=${TOKEN} benchmark.js"
echo ""
