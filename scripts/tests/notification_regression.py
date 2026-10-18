#!/usr/bin/env python3
# coding: utf-8
"""Notification integration tests. ONLY the disposable trade-notification-test stack on 8089.
Requires TEST_ADMIN/TEST_PASSWORD and ALLOW_DISPOSABLE_TEST_WRITES=yes.
Creates synthetic files and accounts; never point at the normal database.
"""
import os,io,zipfile,subprocess,json,csv,time
from xml.sax.saxutils import escape
from concurrent.futures import ThreadPoolExecutor
from http_regression import Client,check,equal

def sql(statement):
    return subprocess.check_output(['docker','exec','-i','trade-notification-test_db_1','psql','-U','trade_user','-d','trade_platform','-At','-v','ON_ERROR_STOP=1'],input=statement.encode()).decode().strip()
def workbook(contract):
    cells={3:{'B':'契約番号(CONTRACT NO)','D':'設備名称','I':'外寸MM','N':'コンテナ','O':'出発港','P':'目的港'},4:{'F':'分解部名称','H':'PCS','I':'（W)','J':'（D)','K':'（H)','L':'KG'},5:{'B':contract,'D':'Test machine','E':'MODEL-TEST','F':'Main body','G':'A1','H':1,'I':1000,'J':2000,'K':3000,'L':100,'M':6,'N':'40HQ*1','O':'PORT-A','P':'PORT-B'}}
    rows=[]
    for n,values in cells.items():
        parts=[]
        for col,value in values.items():
            parts.append('<c r="%s%d"%s>%s</c>'%(col,n,' t="inlineStr"' if isinstance(value,str) else '',('<is><t>'+escape(value)+'</t></is>') if isinstance(value,str) else '<v>'+str(value)+'</v>'))
        rows.append('<row r="%d">%s</row>'%(n,''.join(parts)))
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Packing" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+''.join(rows)+'</sheetData></worksheet>')
    return out.getvalue()
def main():
    assert os.environ['TEST_ADMIN']=='notification_admin'
    sql("INSERT INTO m_user(user_id,user_name,password_hash,role_code) SELECT 'notification_other','Other',password_hash,'ADMIN' FROM m_user WHERE user_id='notification_admin' ON CONFLICT DO NOTHING; INSERT INTO m_user(user_id,user_name,password_hash,role_code) SELECT 'notification_reader','Reader',password_hash,'USER' FROM m_user WHERE user_id='notification_admin' ON CONFLICT DO NOTHING;")
    a=Client();a.login(os.environ['TEST_ADMIN']);b=Client();b.login('notification_other');reader=Client();reader.login('notification_reader');anon=Client()
    check('anonymous denied',lambda:anon.call('/notifications',status=401))
    check('initial empty',lambda:equal(a.call('/notifications')['rows'],[]))
    data=workbook('NOTICE-001');p=a.upload('/imports/packing/preview','synthetic.xlsx',data)
    rows=a.call('/notifications')['rows'];check('preview triggers upload notification',lambda:equal((len(rows),rows[0]['type']),(1,'FILE_UPLOAD')))
    a.upload('/imports/packing/preview','synthetic.xlsx',data)
    check('repeated preview deduplicated',lambda:equal(len(a.call('/notifications')['rows']),1))
    fields={'previewHash':p['fileHash'],'acknowledgeReview':'true'}
    saved=a.upload('/imports/packing','synthetic.xlsx',data,fields)
    rows=a.call('/notifications')['rows'];check('successful import notification',lambda:equal((len(rows),rows[0]['type'],saved['rowCount']),(2,'DATA_IMPORT',1)))
    nid=rows[0]['id'];check('other admin sees no private events',lambda:equal(b.call('/notifications')['rows'],[]))
    check('reader sees no private events',lambda:equal(reader.call('/notifications')['rows'],[]))
    check('other user cannot mark event',lambda:b.call('/notifications/'+str(nid)+'/read','POST',status=404))
    check('mark requires CSRF',lambda:a.call('/notifications/'+str(nid)+'/read','POST',status=403,csrf=False))
    a.call('/notifications/'+str(nid)+'/read','POST');first=a.call('/notifications')['rows'][0]['readAt']
    a.call('/notifications/'+str(nid)+'/read','POST')
    check('read is idempotent',lambda:equal(a.call('/notifications')['rows'][0]['readAt'],first))
    check('unread decreases',lambda:equal(a.call('/notifications/unread-count')['unreadCount'],1))
    b.upload('/imports/packing/preview','bad.xlsx',b'invalid',status=400)
    check('failure persists for actor',lambda:equal(b.call('/notifications')['rows'][0]['type'],'OPERATION_FAILED'))
    check('failure does not leak to another user',lambda:equal(len(a.call('/notifications')['rows']),2))
    a.upload('/imports/packing','synthetic.xlsx',data,fields,status=409)
    check('duplicate import has no duplicate success',lambda:equal(sum(r['type']=='DATA_IMPORT' for r in a.call('/notifications')['rows']),1))
    # Force failure after the batch insert; no successful notification or partial business rows may survive.
    data2=workbook('NOTICE-ROLLBACK');p2=a.upload('/imports/packing/preview','rollback.xlsx',data2)
    sql("CREATE FUNCTION notification_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated rollback test'; END $$; CREATE TRIGGER notification_test_fail BEFORE INSERT ON t_packing_source_row FOR EACH ROW EXECUTE FUNCTION notification_test_fail();")
    try:a.upload('/imports/packing','rollback.xlsx',data2,{'previewHash':p2['fileHash'],'acknowledgeReview':'true'},status=500)
    finally:sql('DROP TRIGGER notification_test_fail ON t_packing_source_row; DROP FUNCTION notification_test_fail();')
    check('business rollback preserved',lambda:equal(sql('SELECT count(*) FROM t_packing_import_batch;'),'1'))
    check('rollback produces failure only',lambda:equal(a.call('/notifications')['rows'][0]['type'],'OPERATION_FAILED'))
    check('no rollback success message',lambda:equal(sum(r['type']=='DATA_IMPORT' for r in a.call('/notifications')['rows']),1))
    # Real CSV path.
    cols='export_no,customer_code,customer_name,equipment_code,equipment_name,model_no,invoice_no,invoice_date,export_date,part_name,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3,container_type,container_qty,record_basis'.split(',')
    values=['NOTICE-CSV','NOTICE-C','Test customer','NOTICE-P','Test machine','TEST','NOTICE-I','2026-10-18','2026-10-18','Part','100','100','100','1','0.001','20DRY','1','DOCUMENTED']
    buf=io.StringIO();w=csv.writer(buf);w.writerow(cols);w.writerow(values)
    a.upload('/imports/csv','synthetic.csv',buf.getvalue().encode())
    check('CSV success event',lambda:equal(a.call('/notifications')['rows'][0]['type'],'DATA_IMPORT'))
    a.upload('/imports/csv','invalid.csv',b'bad',status=400)
    check('CSV failure event',lambda:equal(a.call('/notifications')['rows'][0]['type'],'OPERATION_FAILED'))
    # Concurrent previews of a new file represent the same upload event.
    data3=workbook('NOTICE-CONCURRENT');clients=[Client(),Client()]
    for c in clients:c.login(os.environ['TEST_ADMIN'])
    before=len(a.call('/notifications?size=100')['rows'])
    with ThreadPoolExecutor(2) as pool:list(pool.map(lambda c:c.upload('/imports/packing/preview','concurrent.xlsx',data3),clients))
    check('concurrent event deduplication',lambda:equal(len(a.call('/notifications?size=100')['rows']),before+1))
    # More than one page, generated by genuine failed operations.
    for i in range(22):a.upload('/imports/packing/preview','invalid-%d.xlsx'%i,b'bad',status=400)
    full=a.call('/notifications?size=100')['rows'];page=a.call('/notifications?size=20');rest=a.call('/notifications?size=100&before='+str(page['rows'][-1]['id']))
    check('history stable without omissions',lambda:equal(page['rows']+rest['rows'],full))
    check('newest first',lambda:equal([r['id'] for r in full],sorted([r['id'] for r in full],reverse=True)))
    for q in ['size=0','size=101','before=-1','before=abc']:check('invalid pagination',lambda q=q:a.call('/notifications?'+q,status=400))
    a.call('/notifications/read-all','POST');check('all read',lambda:equal(a.call('/notifications/unread-count')['unreadCount'],0))
    check('all-read isolated',lambda:equal(b.call('/notifications/unread-count')['unreadCount'],1))
    a.call('/auth/logout','POST');a.token='';a.login(os.environ['TEST_ADMIN'])
    check('read state persists after login',lambda:equal(a.call('/notifications/unread-count')['unreadCount'],0))
    check('history persists after login',lambda:equal(len(a.call('/notifications?size=100')['rows']),len(full)))
    print('NOTIFICATION REGRESSION PASSED')
if __name__=='__main__':main()
