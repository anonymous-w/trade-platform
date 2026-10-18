#!/usr/bin/env python3
"""Integration checks on the disposable localhost:8089 stack only.
Set PACKING_TEST_FILE to a local workbook. The file is never copied into this repository.
Uses the TEST_ADMIN/TEST_READER/TEST_PASSWORD and safety guard from http_regression.
"""
import concurrent.futures, io, os, urllib.parse, zipfile, subprocess, decimal, xml.etree.ElementTree as ET
from pathlib import Path
from http_regression import Client,check,equal

def main():
    data=Path(os.environ['PACKING_TEST_FILE']).read_bytes()
    admin=Client();reader=Client();anonymous=Client()
    admin.login(os.environ['TEST_ADMIN']);reader.login(os.environ['TEST_READER'])
    before=admin.call('/exports')['total']
    check('anonymous cannot read packing archive',lambda:anonymous.call('/imports/packing/batches',status=401))
    check('reader cannot preview private upload',lambda:reader.upload('/imports/packing/preview','source.xlsx',data,status=403))
    check('reader cannot save packing',lambda:reader.upload('/imports/packing','source.xlsx',data,{'previewHash':'x','acknowledgeReview':'true'},status=403))
    check('bad extension rejected',lambda:admin.upload('/imports/packing/preview','source.csv',data,status=400))
    check('bad xlsx contents rejected',lambda:admin.upload('/imports/packing/preview','source.xlsx',b'not xlsx',status=400))
    preview=admin.upload('/imports/packing/preview','source.xlsx',data)
    expected=int(os.environ.get('PACKING_EXPECTED_ROWS','2718'))
    check('all workbook detail rows recognized',lambda:equal(preview['rowCount'],expected))
    check('preview is capped and has review samples',lambda:equal((len(preview['rows']),bool(preview['reviewSamples'])),(min(100,expected),True)))
    check('preview does not write records',lambda:equal(admin.call('/imports/packing/batches'),[]))
    fields={'previewHash':preview['fileHash'],'acknowledgeReview':'true'}
    check('stale preview rejected',lambda:admin.upload('/imports/packing','source.xlsx',data,{**fields,'previewHash':'changed'},status=409))
    check('explicit acknowledgement required',lambda:admin.upload('/imports/packing','source.xlsx',data,{'previewHash':preview['fileHash']},status=400))
    # Force a failure after the batch header and first row are inserted. Never target the main DB.
    def db(sql):
        return subprocess.check_output(['docker','exec','-i','trade-platform-excel-test_db_1','psql','-U','trade_user','-d','trade_platform','-v','ON_ERROR_STOP=1','-At'],input=sql.encode()).decode().strip()
    db("CREATE FUNCTION excel_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_row=6 THEN RAISE EXCEPTION 'test rollback' USING ERRCODE='23514'; END IF; RETURN NEW; END $$; CREATE TRIGGER excel_test_fail BEFORE INSERT ON t_packing_source_row FOR EACH ROW EXECUTE FUNCTION excel_test_fail();")
    try:
        check('mid-import database failure rejected',lambda:admin.upload('/imports/packing','source.xlsx',data,fields,status=409))
        check('failed import rolls back header and every row',lambda:equal(db('SELECT (SELECT count(*) FROM t_packing_import_batch)::text || \',\' || (SELECT count(*) FROM t_packing_source_row)::text;'),'0,0'))
    finally:db('DROP TRIGGER excel_test_fail ON t_packing_source_row; DROP FUNCTION excel_test_fail();')
    def save():
        client=Client();client.login(os.environ['TEST_ADMIN'])
        try:return ('ok',client.upload('/imports/packing','source.xlsx',data,fields))
        except AssertionError as e:
            assert e.args[0][1]==409,e
            return ('duplicate',None)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:save(),range(2)))
    check('concurrent duplicate is atomic',lambda:equal(sorted(r[0] for r in results),['duplicate','ok']))
    saved=next(r[1] for r in results if r[0]=='ok');batch=saved['batchId']
    batches=reader.call('/imports/packing/batches')
    check('one batch with creator audit',lambda:equal((len(batches),batches[0]['created_by']),(1,os.environ['TEST_ADMIN'])))
    check('saved row count reconciles',lambda:equal(saved['rowCount'],expected))
    allrows=[]
    for page in range((expected+99)//100):allrows+=reader.call(f'/imports/packing/batches/{batch}/rows?size=100&page={page}')['rows']
    check('pagination has no omissions or duplicates',lambda:equal((len(allrows),len({r['sourceRow'] for r in allrows})),(expected,expected)))
    check('source row numbers preserved',lambda:equal((allrows[0]['sourceRow'],allrows[-1]['sourceRow']),(5,expected+4)))
    # Independently reconcile every physical numeric source cell, not just the preview sample.
    ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
    with zipfile.ZipFile(io.BytesIO(data)) as z: root=ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
    original={c.attrib['r']:c for c in root.findall('.//s:sheetData/s:row/s:c',ns)}
    mapped={'I':'widthMm','J':'depthMm','K':'heightMm','L':'weightKg','M':'volumeM3'}
    checked=0
    for row in allrows:
        for col,field in mapped.items():
            cell=original.get(col+str(row['sourceRow']))
            if cell is None or cell.attrib.get('t','n')!='n':continue
            value=cell.find('s:v',ns)
            if value is None or not value.text:continue
            expected_value=decimal.Decimal(value.text)
            assert row['rawCells'][col]['value']==value.text,(row['sourceRow'],col,'raw changed')
            assert row[field] is not None,(row['sourceRow'],col,'number lost')
            assert abs(decimal.Decimal(str(row[field]))-expected_value)<=max(abs(expected_value)*decimal.Decimal('1e-14'),decimal.Decimal('1e-20'))
            checked+=1
    print('PASS all source numeric cells reconciled:',checked)
    check('preview matches saved values' ,lambda:equal(allrows[:100],preview['rows']))
    count=sum(bool(r['warnings']) for r in allrows)
    check('review filter reconciles',lambda:equal(reader.call(f'/imports/packing/batches/{batch}/rows?reviewOnly=true')['total'],count))
    q=urllib.parse.quote(allrows[0]['contractNo'])
    found=reader.call(f'/imports/packing/batches/{batch}/rows?keyword={q}')
    check('contract query returns source rows',lambda:equal(found['rows'][0]['contractNo'],allrows[0]['contractNo']))
    check('literal wildcard search is escaped',lambda:equal(reader.call(f'/imports/packing/batches/{batch}/rows?keyword=%25_%25')['total'],0))
    check('rename cannot bypass duplicate check',lambda:admin.upload('/imports/packing','renamed.xlsx',data,fields,status=409))
    # Repacking ZIP changes bytes but not cell contents; content digest must still identify it.
    buf=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as old,zipfile.ZipFile(buf,'w',zipfile.ZIP_STORED) as new:
        for name in old.namelist():new.writestr(name,old.read(name))
    repacked=buf.getvalue();p2=admin.upload('/imports/packing/preview','resaved.xlsx',repacked)
    check('semantic duplicate preview',lambda:equal(len(p2['duplicates']),1))
    check('semantic duplicate rejected',lambda:admin.upload('/imports/packing','resaved.xlsx',repacked,{'previewHash':p2['fileHash'],'acknowledgeReview':'true'},status=409))
    check('source import does not invent exports',lambda:equal(admin.call('/exports')['total'],before))
    check('invalid archive page rejected',lambda:reader.call(f'/imports/packing/batches/{batch}/rows?page=-1',status=400))
    check('missing archive rejected',lambda:reader.call('/imports/packing/batches/999999999/rows',status=404))
    admin.call('/auth/logout','POST');admin.token=''
    check('logout closes archive access',lambda:admin.call('/imports/packing/batches',status=401))
    print(f'PACKING REGRESSION PASSED: {expected} rows; {count} require review')
if __name__=='__main__':main()
