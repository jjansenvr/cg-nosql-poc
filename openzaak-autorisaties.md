# Open Zaak autorisatieregels

Samengesteld uit de Open Zaak-broncode (`master`-branch). De `has_auth`-logica uit `vng-api-common` is niet geraadpleegd (404); de vergelijking per zaaktype en vertrouwelijkheid komt uit `permissions.py` en de ZGW-standaard.

> [!NOTE]
> `docker-compose.yml` gebruikt `openzaak/open-zaak:latest`. Een andere Open Zaak-versie kan afwijken van `master`.

## Model

- **Applicatie:** een consumer, met JWT-`client_id`s en een secret. In `benchmarks/setup-openzaak.sh` staat `heeft_alle_autorisaties: True` voor de benchmark-client, dus die omzeilt alle regels hieronder.
- **Autorisatie per zaaktype:** de ZGW-spec legt per zaaktype vast welke scopes een consumer heeft en wat de maximale vertrouwelijkheidaanduiding is.
- **CatalogusAutorisatie:** geeft per `(applicatie, catalogus, component)` scopes en `max_vertrouwelijkheidaanduiding` (inclusief) voor alle types in een catalogus. Geldt voor ZRC, DRC en BRC ([models.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/autorisaties/models.py)).
- **Toetsing in de Zaken API:** `ZaakAuthRequired` toetst de scopes van de actie, het `zaaktype` en de `vertrouwelijkheidaanduiding` van de zaak ([permissions.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/zaken/api/permissions.py)). Geneste resources gebruiken `ZaakNestedAuthRequired`, dat dezelfde toets op de bovenliggende zaak doet.
- **Weigering:** de spec eist HTTP 403 als een operatie niet is toegestaan.

## Scopes per component

| Component | Scopes |
|---|---|
| Zaken ([scopes.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/zaken/api/scopes.py)) | `zaken.lezen`, `zaken.aanmaken`, `zaken.bijwerken`, `zaken.statussen.toevoegen`, `zaken.verwijderen`, `zaken.geforceerd-bijwerken`, `zaken.heropenen` |
| Documenten ([scopes.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/documenten/api/scopes.py)) | `documenten.lezen`, `documenten.aanmaken`, `documenten.bijwerken`, `documenten.verwijderen`, `documenten.lock`, `documenten.geforceerd-unlock` |
| Besluiten ([scopes.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/besluiten/api/scopes.py)) | `besluiten.lezen`, `besluiten.aanmaken`, `besluiten.bijwerken`, `besluiten.verwijderen` |
| Catalogi ([scopes.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/catalogi/api/scopes.py)) | `catalogi.lezen`, `catalogi.schrijven`, `catalogi.geforceerd-schrijven`, `catalogi.geforceerd-verwijderen` |
| Autorisaties ([scopes.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/autorisaties/api/scopes.py)) | `autorisaties.lezen`, `autorisaties.bijwerken` |

### Wat de Zaken-scopes toestaan

| Scope | Toestaan |
|---|---|
| `zaken.lezen` | zaken opsommen en doorzoeken, zaakdetails, statussen en zaakobjecten lezen |
| `zaken.aanmaken` | zaak aanmaken, eerste status zetten, zaakobjecten en rollen aanmaken |
| `zaken.bijwerken` | attributen van een zaak wijzigen |
| `zaken.statussen.toevoegen` | statussen toevoegen aan een zaak |
| `zaken.verwijderen` | zaken verwijderen |
| `zaken.geforceerd-bijwerken` | attributen van alle zaken wijzigen, ook gesloten zaken |
| `zaken.heropenen` | zaak heropenen door een status na de eindstatus aan te maken |

## Vereiste scope per endpoint (Zaken API)

Een `\|` betekent: één van de scopes volstaat. Bron: [viewsets.py](https://github.com/open-zaak/open-zaak/blob/master/src/openzaak/components/zaken/api/viewsets.py).

| Resource | Lezen (list/retrieve) | Schrijven |
|---|---|---|
| `zaken` | `zaken.lezen` (ook zoeken) | create: `zaken.aanmaken`; update en partial_update: `zaken.bijwerken` \| `zaken.geforceerd-bijwerken`; destroy: `zaken.verwijderen` |
| `statussen` | `zaken.lezen` | create: `zaken.aanmaken` \| `zaken.statussen.toevoegen` \| `zaken.heropenen` |
| `zaakobjecten` | `zaken.lezen` | create: `zaken.aanmaken` \| `zaken.bijwerken` \| `zaken.geforceerd-bijwerken`; update: `zaken.bijwerken` \| `zaken.geforceerd-bijwerken`; destroy: `zaken.bijwerken` \| `zaken.geforceerd-bijwerken` \| `zaken.verwijderen` |
| `zaakinformatieobjecten` | `zaken.lezen` | zelfde regels als `zaakobjecten` |
| `rollen`, `zaakeigenschappen`, `resultaten`, `klantcontacten` | `zaken.lezen` | create/update/destroy: `zaken.bijwerken` \| `zaken.geforceerd-bijwerken` (`klantcontacten` alleen create) |
| `zaakbesluiten`, `zaakcontactmomenten`, `zaakverzoeken`, `zaaknotities` | `zaken.lezen` | create/destroy: `zaken.bijwerken` (`zaaknotities` ook update) |
| `reserveerzaaknummer` | n.v.t. | create: `zaken.aanmaken` |

## Aanvullende regels in de code

- **Gesloten zaken:** zonder `zaken.geforceerd-bijwerken` zijn gesloten zaken niet te wijzigen.
- **Eerste status:** met alleen `zaken.aanmaken` mag je maar één status per zaak zetten. Meer statussen vragen `zaken.statussen.toevoegen` of `zaken.heropenen`. Een status na de eindstatus vraagt `zaken.heropenen`.
- **Acties:** de acties zaak registreren, opschorten, bijwerken, verlengen en afsluiten combineren scopes met EN/OF. Bijvoorbeeld bijwerken-of-geforceerd én aanmaken-of-statussen-of-heropenen.
