#!/usr/bin/env python3
"""Mutating regression checks. Run ONLY against a disposable audit database on localhost:8089.
Requires TEST_ADMIN, TEST_READER, TEST_PASSWORD environment variables.
Creates records prefixed AUDIT-; never run on the normal application database.
"""
import csv, io, json, os, secrets, urllib.request, urllib.error, urllib.parse, http.cookiejar
BASE='http://127.0.0.1:8089'
assert os.environ.get('ALLOW_DISPOSABLE_TEST_WRITES')=='yes', 'Set ALLOW_DISPOSABLE_TEST_WRITES=yes for the isolated test stack only'
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.token=''
    def call(self,path,method='GET',body=None,headers=None,status=200,csrf=True):
        h=dict(headers or {})
        if method!='GET' and csrf:
            if not self.token:self.refresh()
            h['X-XSRF-TOKEN']=self.token
        req=urllib.request.Request(BASE+'/api'+path,data=body,method=method,headers=h)
        try:r=self.opener.open(req,timeout=20)
        except urllib.error.HTTPError as e:r=e
        raw=r.read()
        assert r.status==status,(path,r.status,status,raw[:400])
        return json.loads(raw) if 'application/json' in r.headers.get('Content-Type','') else raw
    def refresh(self):self.token=self.call('/auth/csrf')['token']
    def login(self,name,password=None,status=200):
        self.refresh();self.call('/auth/login','POST',urllib.parse.urlencode({'username':name,'password':password or os.environ['TEST_PASSWORD']}).encode(),{'Content-Type':'application/x-www-form-urlencoded'},status=status);self.token=''
    def upload(self,path,name,data,fields=None,status=200):
        boundary='audit'+secrets.token_hex(12);parts=[]
        for k,v in (fields or {}).items():parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
        parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode(), data, f'\r\n--{boundary}--\r\n'.encode()]
        return self.call(path,'POST',b''.join(parts),{'Content-Type':'multipart/form-data; boundary='+boundary},status=status)
def check(name,fn):
    fn();print('PASS',name,flush=True)
def equal(a,b):assert a==b,(a,b)
def main():
    admin=Client();reader=Client();anon=Client()
    check('anonymous cannot read exports',lambda:anon.call('/exports',status=401))
    check('login requires CSRF',lambda:anon.call('/auth/login','POST',b'username=x&password=x',{'Content-Type':'application/x-www-form-urlencoded'},status=403,csrf=False))
    admin.login(os.environ['TEST_ADMIN']);reader.login(os.environ['TEST_READER'])
    check('authenticated user',lambda:equal(admin.call('/auth/me')['username'],os.environ['TEST_ADMIN']))
    for path in ['/exports?page=abc','/exports/not-a-number','/exports?dateFrom=2026-12-31&dateTo=2026-01-01','/sales?page=-1','/containers?basis=INVALID']:
        check('bad input '+path,lambda p=path:admin.call(p,status=400))
    for path in ['/exports/999999999','/sales/999999999','/documents/999999999/content']:
        check('missing record '+path,lambda p=path:admin.call(p,status=404))
    total=admin.call('/exports')['total']
    pages=[admin.call('/exports?size=20&page='+str(i))['rows'] for i in range((total+19)//20)]
    check('pagination stable and complete',lambda:equal(len({row['export_id'] for rows in pages for row in rows}),total))
    check('literal wildcard search',lambda:equal(admin.call('/exports?keyword=%25_%25')['total'],0))
    row=pages[0][0];keyword=urllib.parse.quote(row['export_no'])
    check('export number keyword',lambda:equal(admin.call('/exports?keyword='+keyword)['total'],1))
    csv_rows=list(csv.reader(io.StringIO(admin.call('/exports/export.csv?keyword='+keyword).decode('utf-8-sig'))))
    check('filtered CSV matches list',lambda:equal(len(csv_rows),2))
    detail=admin.call('/exports/'+str(row['export_id']))
    check('export detail includes packing',lambda:equal('packing' in detail['items'][0],True))
    for path in ['/sales','/containers','/records/monitoring','/records/documents']:
        check('read endpoint '+path,lambda p=path:reader.call(p))
    recent=admin.call('/records/documents')
    for doc in recent[:3]:
        check('existing PDF '+str(doc['document_id']),lambda d=doc:equal(admin.call('/documents/'+str(d['document_id'])+'/content')[:5],b'%PDF-'))
    check('reader cannot import',lambda:reader.upload('/imports/csv','test.csv',b'x',status=403))
    check('reader cannot upload',lambda:reader.upload('/documents','test.pdf',b'%PDF-1.4',{'exportId':row['export_id'],'type':'OTHER'},status=403))
    cols='export_no,customer_code,customer_name,equipment_code,equipment_name,model_no,invoice_no,invoice_date,export_date,part_name,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3,container_type,container_qty,record_basis'.split(',')
    prefix='AUDIT-'+secrets.token_hex(4)
    values=[prefix,prefix,'Audit Customer',prefix,'Audit Equipment','Audit Model',prefix,'2026-10-12','2026-10-12','Part A','100','200','300','10','0.006','20DRY','1','DOCUMENTED']
    def contents(rows,bom=False):
        buf=io.StringIO();w=csv.writer(buf);w.writerow(cols);w.writerows(rows);return (('\ufeff' if bom else '')+buf.getvalue()).encode()
    conflict=values.copy();conflict[2]='Different Customer'
    check('conflicting CSV header rejected',lambda:admin.upload('/imports/csv','bad.csv',contents([values,conflict]),status=400))
    check('rejected CSV has no partial export',lambda:equal(admin.call('/exports?keyword='+prefix)['total'],0))
    invalid=values.copy();invalid[7]='not-date'
    check('invalid date rejected',lambda:admin.upload('/imports/csv','bad.csv',contents([invalid]),status=400))
    invalid=values.copy();invalid[10]='-1'
    check('negative dimension rejected',lambda:admin.upload('/imports/csv','bad.csv',contents([invalid]),status=400))
    second=values.copy();second[9]='Part B'
    result=admin.upload('/imports/csv','bom.csv',contents([values,second],True))
    check('UTF-8 BOM CSV imports grouped rows',lambda:equal((result['createdExports'],result['packingRows']),(1,2)))
    check('duplicate import rejected',lambda:admin.upload('/imports/csv','duplicate.csv',contents([values]),status=409))
    eid=admin.call('/exports?keyword='+prefix)['rows'][0]['export_id'];detail=admin.call('/exports/'+str(eid))
    check('CSV grouping and audit creator',lambda:equal((len(detail['items'][0]['packing']),detail['export']['created_by']),(2,os.environ['TEST_ADMIN'])))
    # A late database conflict must roll back earlier rows and newly created master records.
    fresh=values.copy();fresh[0]=prefix+'-rollback';fresh[1]=prefix+'-new';fresh[3]=prefix+'-new'
    check('invoice conflict rolls back',lambda:admin.upload('/imports/csv','bad.csv',contents([fresh]),status=409))
    check('no partial export after database conflict',lambda:equal(admin.call('/exports?keyword='+fresh[0])['total'],0))
    check('missing PDF owner',lambda:admin.upload('/documents','a.pdf',b'%PDF-1.4',{'exportId':999999999,'type':'OTHER'},status=404))
    check('invalid PDF signature',lambda:admin.upload('/documents','a.pdf',b'not a pdf',{'exportId':eid,'type':'OTHER'},status=400))
    check('ambiguous PDF owner',lambda:admin.upload('/documents','a.pdf',b'%PDF-1.4',{'exportId':eid,'orderId':1,'type':'OTHER'},status=400))
    source=admin.call('/documents/'+str(recent[0]['document_id'])+'/content')
    uploaded=admin.upload('/documents','audit.pdf',source,{'exportId':eid,'type':'OTHER'})
    check('PDF bytes preserved',lambda:equal(admin.call('/documents/'+str(uploaded['documentId'])+'/download'),source))
    admin.call('/auth/logout','POST');admin.token=''
    check('logout invalidates session',lambda:admin.call('/auth/me',status=401))
    # Test account lockout last; this reader account belongs to the disposable fixture.
    for _ in range(5):anon.login(os.environ['TEST_READER'],'incorrect-password',401)
    check('five failures lock account',lambda:anon.login(os.environ['TEST_READER'],status=401))
    print('ALL REGRESSION CHECKS PASSED')
if __name__=='__main__':main()
