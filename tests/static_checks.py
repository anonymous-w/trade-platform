"""Offline source integrity checks; not a replacement for build/integration tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
assert len(list((ROOT/'frontend/src/views').glob('*.vue')))>=9
ET.parse(ROOT/'backend/pom.xml')
readme=(ROOT/'README.md').read_text()
assert 'docker compose up --build' in readme
sql=(ROOT/'backend/src/main/resources/db/migration/V1__unified_schema.sql').read_text()
tables=set(re.findall(r'CREATE TABLE (\w+) \(',sql))
assert len(tables)==15
for fk in re.findall(r'REFERENCES (\w+) \(',sql):
    assert fk in tables, fk
for page in (ROOT/'docs/system-design-v0.7.md').read_text().splitlines():
    for asset in re.findall(r'\!\[[^]]*]\(([^)]+)\)',page):
        assert (ROOT/'docs'/asset).exists(),asset
for file in (ROOT/'frontend/src/views').glob('*.vue'):
    assert '<template>' in file.read_text(),file
print('OK: 15 tables, all FK targets, image assets, basic frontend & packaging structure')
