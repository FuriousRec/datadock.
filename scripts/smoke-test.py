"""Exercise the packaged API against an empty, disposable database."""
import json
import os
from pathlib import Path
import time
from decimal import Decimal
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

BASE = 'http://127.0.0.1:8080'
KEY = os.environ['APP_API_KEY']


def request(path, data=None, authenticated=True, content_type=None):
    headers = {'X-API-Key': KEY} if authenticated else {}
    if content_type:
        headers['Content-Type'] = content_type
    try:
        with urlopen(Request(BASE + path, data=data, headers=headers), timeout=10) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()


for attempt in range(90):
    try:
        if request('/actuator/health/readiness', authenticated=False)[0] == 200:
            break
    except (URLError, TimeoutError):
        pass
    time.sleep(2)
else:
    raise SystemExit('Application did not become ready within the startup window')

assert request('/api/v1/sales', authenticated=False)[0] == 401
boundary = 'DataDockSmokeBoundary'
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        'filename="sales.csv"\r\nContent-Type: text/csv\r\n\r\n').encode()
body += Path('examples/sales.csv').read_bytes()
body += f'\r\n--{boundary}--\r\n'.encode()
content_type = f'multipart/form-data; boundary={boundary}'
status, response = request('/api/v1/imports', body, content_type=content_type)
assert status == 201, (status, response)
assert request('/api/v1/imports', body, content_type=content_type)[0] == 409
status, response = request('/api/v1/analytics/summary?from=2026-09-01&to=2026-09-30')
assert status == 200, (status, response)
summary = json.loads(response, parse_float=Decimal)
assert summary == {'rows': 3, 'units': 6, 'revenue': Decimal('357.30'), 'currency': 'EUR'}, summary
print('PASS: packaged API readiness, authentication, import, duplicate rejection, 3 rows / 6 units / EUR 357.30')
