#!/usr/bin/env python3
# coding: utf-8
"""Only disposable trade-deadline-test on 8089. DEADLINE_TEST_FILE is a local private fixture.
No real workbook contents or exports are stored in this repository.
"""
import os,sys,json,io,zipfile,subprocess,xml.etree.ElementTree as E,hashlib
from concurrent.futures import ThreadPoolExecutor
from http_regression import Client,check,equal
NS={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
def sql(statement):return subprocess.check_output(['docker','exec','-i','trade-deadline-test_db_1','psql','-U','trade_user','-d','trade_platform','-At','-v','ON_ERROR_STOP=1'],input=statement.encode()).decode().strip()
def variant(data,changes=None,tag='variant'):
    src=zipfile.ZipFile(io.BytesIO(data));out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as target:
        for item in src.infolist():
            content=src.read(item.filename)
            if item.filename=='xl/worksheets/sheet5.xml' and changes:
                root=E.fromstring(content)
                for c in root.findall('.//s:c',NS):
                    if c.attrib.get('r') in changes:
                        value=changes[c.attrib['r']]
                        for child in list(c):c.remove(child)
                        if value is None:c.attrib.pop('t',None)
                        elif isinstance(value,int):c.attrib.pop('t',None);E.SubElement(c,'{'+NS['s']+'}v').text=str(value)
                        else:c.set('t','inlineStr');E.SubElement(E.SubElement(c,'{'+NS['s']+'}is'),'{'+NS['s']+'}t').text=value
                content=E.tostring(root,encoding='utf-8',xml_declaration=True)
            target.writestr(item.filename,content)
        target.comment=tag.encode()
    return out.getvalue()
def preview(c,data):return c.upload('/imports/deadlines/preview','deadline-test.xlsx',data)
def save(c,data,p,choices=None,status=200):
    return c.upload('/imports/deadlines','deadline-test.xlsx',data,{'previewToken':p['previewToken'],'acknowledgeReview':'true','decisions':json.dumps(choices or {str(r['data']['sourceRow']):r['action'] for r in p['rows']})},status=status)
def main():
    assert os.environ['TEST_ADMIN']=='deadline_admin'
    data=open(os.environ['DEADLINE_TEST_FILE'],'rb').read();a=Client();a.login('deadline_admin');anon=Client()
    sql("INSERT INTO m_user(user_id,user_name,password_hash,role_code) SELECT 'deadline_reader','Reader',password_hash,'USER' FROM m_user WHERE user_id='deadline_admin' ON CONFLICT DO NOTHING;")
    reader=Client();reader.login('deadline_reader')
    check('anonymous denied',lambda:anon.call('/deadlines',status=401))
    check('reader cannot preview',lambda:reader.upload('/imports/deadlines/preview','x.xlsx',data,status=403))
    p=preview(a,data);check('two grouped business records',lambda:equal(p['recordCount'],2));check('two new no errors',lambda:equal((p['newCount'],p['existingCount'],p['errorCount']),(2,0,0)))
    first=p['rows'][0]['data'];second=p['rows'][1]['data']
    check('merged block boundaries',lambda:equal([(r['data']['sourceRow'],r['data']['endRow']) for r in p['rows']],[(5,7),(8,11)]))
    check('numeric permit and expiry dates',lambda:equal((first['permitDate'],first['validUntil']),('2024-01-05','2024-07-05')))
    check('month-end due dates',lambda:equal((first['reportDueDate'],first['years'][0]['dueDate']),('2024-10-31','2025-01-31')))
    check('labelled completion separate from plan',lambda:equal((first['years'][0]['completedDate'],first['years'][0]['plannedDate'],first['years'][1]['plannedDate']),('2024-11-22',None,'2025-01-08')))
    check('alternative annual row not interpreted as completion',lambda:equal((second['years'][0]['plannedDate'],second['years'][0]['completedDate']),(None,None)))
    check('alternative raw retained',lambda:equal('２０２６' in second['years'][0]['rawText'],True))
    check('conditions joined by contract and permit',lambda:equal(all(len(r['data']['conditions'])>200 for r in p['rows']),True))
    check('preview creates no business rows',lambda:equal(a.call('/deadlines')['total'],0))
    # Confirmation and snapshot validation must be enforced server-side.
    check('confirmation required',lambda:a.upload('/imports/deadlines','test.xlsx',data,{'previewToken':p['previewToken'],'decisions':'{}'},status=400))
    check('changed file rejected',lambda:save(a,variant(data,tag='changed-before-save'),p,status=409))
    other=Client();other.login('deadline_admin')
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda c:save(c,data,p),[a,other]))
    check('concurrent submissions only create once',lambda:equal(sorted(r['added'] for r in results),[0,2]))
    check('two shared records',lambda:equal(a.call('/deadlines')['total'],2))
    batch=next(r['importId'] for r in results if r['added']==2)
    check('original file retained exactly',lambda:equal(hashlib.sha256(reader.call('/imports/deadlines/'+str(batch)+'/file')).digest(),hashlib.sha256(data).digest()))
    listing=reader.call('/deadlines?contract=SDK112');check('contract search',lambda:equal(listing['total'],1));pid=listing['rows'][0]['permit_id']
    detail=reader.call('/deadlines/'+str(pid));check('five annual rows',lambda:equal(len(detail['years']),5))
    check('shared detail IDs',lambda:equal(detail['record']['permit_id'],pid))
    check('next check not fabricated',lambda:equal(listing['rows'][0]['next_check_date'],None))
    check('state not auto completed',lambda:equal(listing['rows'][0]['monitor_status'],'要確認'))
    for q in ['product=VP600','model=MA34511','permit=G-NI-23-S14511','person=YUKI&contract=SDK112','dateType=validUntil&dateFrom=2024-07-01&dateTo=2024-07-31']:
        check('database search '+q,lambda q=q:equal(reader.call('/deadlines?'+q)['total'],1))
    check('literal SQL wildcard',lambda:equal(reader.call('/deadlines?contract=%25_%25')['total'],0))
    check('pagination stable',lambda:equal(len({reader.call('/deadlines?size=1&page='+str(i))['rows'][0]['permit_id'] for i in range(2)}),2))
    for q in ['dateType=invalid','dateFrom=2025-01-01&dateTo=2024-01-01','size=101','page=-1','dateFrom=invalid']:
        check('invalid search rejected',lambda q=q:reader.call('/deadlines?'+q,status=400))
    repeat=preview(a,data);check('existing defaults skip',lambda:equal([r['action'] for r in repeat['rows']],['SKIP','SKIP']))
    r=save(a,data,repeat);check('repeat file creates no duplicates',lambda:equal((r['added'],r['skipped'],a.call('/deadlines')['total']),(0,2,2)))
    # Preserve future manual monitoring fields when explicitly updating changed source data.
    sql("UPDATE t_deadline_year SET manual_status='CONFIRMED_TEST',manual_planned_date='2026-03-01',manual_completed_date='2026-03-02',manual_notes='Manual test note' WHERE permit_id=%d AND year_no=1;"%pid)
    changed=variant(data,{'I5':45479},'changed-expiry');up=preview(a,changed);choices={str(r['data']['sourceRow']):('UPDATE' if r['existingId']==pid else 'SKIP') for r in up['rows']};updated=save(a,changed,up,choices)
    check('explicit update and skip counts',lambda:equal((updated['updated'],updated['skipped']),(1,1)))
    d=reader.call('/deadlines/'+str(pid));check('source dates updated',lambda:equal(d['record']['valid_until'],'2024-07-06'))
    y=d['years'][0];check('manual monitoring preserved',lambda:equal((y['manual_status'],y['manual_planned_date'],y['manual_completed_date'],y['manual_notes']),('CONFIRMED_TEST','2026-03-01','2026-03-02','Manual test note')))
    check('record ID stable after update',lambda:equal(d['record']['permit_id'],pid))
    # Stale preview rejected if another writer changed the record version.
    stale=variant(data,tag='stale');sp=preview(a,stale);sql('UPDATE t_deadline_permit SET version=version+1 WHERE permit_id=%d;'%pid)
    check('stale preview rejected',lambda:save(a,stale,sp,status=409))
    # Mixed bad/good source blocks report failure without fabricating required fields.
    bad=variant(data,{'F5':None,'G5':None},'mixed-invalid');bp=preview(a,bad);check('invalid block reported',lambda:equal(bp['errorCount'],1));br=save(a,bad,bp)
    check('mixed result counts',lambda:equal((br['added'],br['failed'],br['skipped']),(0,1,1)))
    # A skipped new file may later be intentionally added; history does not lock it out forever.
    new=variant(data,{'C5':'SYN-DEADLINE-NEW','C8':'SYN-DEADLINE-NEW2'},'skip-then-add');np=preview(a,new);save(a,new,np,{str(r['data']['sourceRow']):'SKIP' for r in np['rows']});np2=preview(a,new);nr=save(a,new,np2)
    check('previously skipped new records can be added',lambda:equal(nr['added'],2))
    check('history stores row actions',lambda:equal(len(reader.call('/imports/deadlines/'+str(batch)+'/rows')),2))
    a.call('/auth/logout','POST');a.token='';a.login('deadline_admin')
    check('data survives relogin',lambda:equal(a.call('/deadlines')['total'],4))
    check('missing detail 404',lambda:a.call('/deadlines/9999999',status=404))
    print('DEADLINE REGRESSION PASSED')
if __name__=='__main__':main()
