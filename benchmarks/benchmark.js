import http from 'k6/http';
import { check, group } from 'k6';
import { Trend } from 'k6/metrics';

const volledigDossierDuur = new Trend('volledig_dossier_ms');

export const options = {
  vus: 50,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const TARGET = __ENV.TARGET || 'rel-agg';
const ZAAK_UUID = __ENV.ZAAK_UUID || '00000000-0000-4000-8000-000000000042';

const CONFIGS = {
  'nosql-agg': {
    baseUrl: 'http://host.docker.internal:8080/api/v1',
    style: 'agg',
    host: 'localhost:8080',
  },
  'nosql-zgw': {
    baseUrl: 'http://host.docker.internal:8080/zaken/api/v1',
    style: 'zgw',
    host: 'localhost:8080',
  },
  'rel-agg': {
    baseUrl: 'http://host.docker.internal:8080/rel/api/v1',
    style: 'agg',
    host: 'localhost:8080',
  },
  'rel-zgw': {
    baseUrl: 'http://host.docker.internal:8080/rel/zaken/api/v1',
    style: 'zgw',
    host: 'localhost:8080',
  },
};

const cfg = CONFIGS[TARGET];

const HEADERS = {
  'Content-Type': 'application/json',
  'Accept-Crs': 'EPSG:4326',
  'Host': cfg.host,
};

export default function () {
  group(`Benchmark: ${TARGET}`, function () {
    const start = Date.now();

    if (cfg.style === 'agg') {
      const res = http.get(`${cfg.baseUrl}/zaken/${ZAAK_UUID}`, { headers: HEADERS });
      check(res, {
        'Status 200': (r) => r.status === 200,
      });
    } else {
      const publiekeZaakUrl = `http://${cfg.host}/zaken/api/v1/zaken/${ZAAK_UUID}`;
      const responses = http.batch([
        ['GET', `${cfg.baseUrl}/zaken/${ZAAK_UUID}`, null, { headers: HEADERS }],
        ['GET', `${cfg.baseUrl}/statussen?zaak=${publiekeZaakUrl}`, null, { headers: HEADERS }],
        ['GET', `${cfg.baseUrl}/rollen?zaak=${publiekeZaakUrl}`, null, { headers: HEADERS }],
        ['GET', `${cfg.baseUrl}/zaakobjecten?zaak=${publiekeZaakUrl}`, null, { headers: HEADERS }],
        ['GET', `${cfg.baseUrl}/zaakinformatieobjecten?zaak=${publiekeZaakUrl}`, null, { headers: HEADERS }],
      ]);

      check(responses, {
        '1. Zaak 200': (rs) => rs[0].status === 200,
        '2. Statussen 200': (rs) => rs[1].status === 200,
        '3. Rollen 200': (rs) => rs[2].status === 200,
        '4. Zaakobjecten 200': (rs) => rs[3].status === 200,
        '5. Zaakinformatieobjecten 200': (rs) => rs[4].status === 200,
      });
    }

    volledigDossierDuur.add(Date.now() - start);
  });
}
