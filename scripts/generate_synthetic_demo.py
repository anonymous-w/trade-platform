from __future__ import annotations
from pathlib import Path
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
import uuid, csv, json
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.colors import HexColor

base = Path(__file__).resolve().parents[1]
root = base/'samples'/'synthetic-demo'
pdfs = root/'documents'
pdfs.mkdir(parents=True,exist_ok=True)

def q(s):
    if s is None:return 'NULL'
    if isinstance(s,bool):return 'TRUE' if s else 'FALSE'
    if isinstance(s,(int,float,Decimal)):return str(s)
    if isinstance(s,date):return "DATE '"+s.isoformat()+"'"
    return "'" + str(s).replace("'","''") + "'"

customers = [
 ('北海道精密機器','Hokkaido Precision Trading','JP'),('東北マシナリー','Tohoku Machinery Trading','JP'),
 ('関東産業設備','Kanto Industrial Systems','JP'),('北陸技術貿易','Hokuriku Tech Trading','JP'),
 ('東海エンジニアリング','Tokai Engineering','JP'),('関西設備商会','Kansai Equipment Trading','JP'),
 ('中国地方機械','Chugoku Industrial Machines','JP'),('四国設備技研','Shikoku Equipment Lab','JP'),
 ('九州精密商事','Kyushu Precision Trading','JP'),('青島機器商貿','Qingdao Equipment Import','CN'),
 ('天津工業有限公司','Tianjin Manufacturing Import','CN'),('釜山産業','Busan Industry Supply','KR'),
 ('台北設備公司','Taipei Equipment Trade','TW'),('大阪機械商事','Osaka Machinery Group','JP'),
 ('ハノイ設備技術','Hanoi Equipment Solutions','VN'),('バンコク工業','Bangkok Industrial Trade','TH')
]
products = [
 ('SPG-X','中古プロファイル研削盤','Used Profile Grinding Machine','WAIDA'),
 ('SPG-X2','高精度プロファイル研削盤','Precision Profile Grinder','WAIDA'),
 ('SPG-XL','大型プロファイル研削盤','Large Profile Grinder','WAIDA'),
 ('MC-500','マシニングセンタ','Machining Center','MAKINO'),
 ('MC-500A','マシニングセンタ改良型','Machining Center A','MAKINO'),
 ('MC-750','立形マシニングセンタ','Vertical Machining Center','OKUMA'),
 ('SL-200','CNC旋盤','CNC Lathe','OKUMA'),
 ('SL-220','精密CNC旋盤','Precision CNC Lathe','OKUMA'),
 ('NC-400','NCフライス盤','NC Milling Machine','MAZAK'),
 ('FM-300','フライス盤','Milling Machine','SHIZUOKA'),
 ('GR-100','平面研削盤','Surface Grinder','OKAMOTO'),
 ('GR-120','円筒研削盤','Cylindrical Grinder','OKAMOTO'),
 ('EDM-250','放電加工機','Electrical Discharge Machine','SODICK'),
 ('EDM-350','ワイヤ放電加工機','Wire EDM','SODICK'),
 ('PR-250','油圧プレス機','Hydraulic Press','AIDA'),
 ('PR-500','大型プレス機','Large Press','AIDA'),
 ('BD-100','ボール盤','Drilling Machine','KIRA'),
 ('VM-600','立形旋盤','Vertical Lathe','TOSHIBA')
]
records={'customers':[], 'products':[], 'orders':[], 'order_items':[], 'exports':[], 'export_items':[], 'invoices':[], 'packing_headers':[], 'packing_lines':[], 'containers':[], 'monitor':[], 'monitor_checks':[], 'documents':[]}
S=[]
S.append('''-- SYN26 synthetic demonstration fixtures for trade-platform-v0.1.
-- TEST DATA ONLY: not actual shipping records, customs documents, nor verification.
-- No existing rows modified or deleted. Seed the dev/test DB only.
BEGIN;
DO $synseed$
DECLARE
  cid bigint;
  pid bigint;
  oid bigint;
  eid bigint;
  iid bigint;
  eiid bigint;
  plid bigint;
  mid bigint;
BEGIN
  IF EXISTS (SELECT 1 FROM t_export WHERE export_no='SYN26-EXP-0001') THEN
    RAISE EXCEPTION 'SYN26 demo data already exists; aborting to prevent duplicates';
  END IF;
''')

for i,(jp,en,country) in enumerate(customers,1):
    c=f'SYN26-C{i:02d}'
    S.append(f"  INSERT INTO m_customer (customer_code, customer_name,customer_name_en,country_code,address1,contact_person,remarks) VALUES ({q(c)},{q('【DEMO】'+jp)},{q('DEMO '+en)}, {q(country)},'DEMO TEST ADDRESS - NOT REAL','TEST STAFF','Synthetic demo fixture') RETURNING customer_id INTO cid;")
    records['customers'].append([c,'【DEMO】'+jp,'DEMO '+en,country])
for i,(model,jp,en,mfr) in enumerate(products,1):
    code=f'SYN26-P{i:02d}'
    S.append(f"  INSERT INTO m_product (product_code,product_name,product_name_en,model_no,manufacturer,unit_code,remarks) VALUES ({q(code)},{q(jp)},{q(en)},{q(model)},{q(mfr)},'SET','DEMO - synthetic master data') RETURNING product_id INTO pid;")
    records['products'].append([code,model,jp,en,mfr])

order_meta=[]
for i in range(1,33):
    code=f'SYN26-SO-{i:04d}'
    customer_idx=(i*3+2)%len(customers)+1
    model_idx=(i*7+2)%len(products)+1
    price=2_300_000+model_idx*445_000+(i%5)*125_000
    quantity=2 if i%9==0 else 1
    total=price*quantity
    dt=date(2024,3,11)+timedelta(days=i*20)
    status=['CONFIRMED','SHIPPED','CONFIRMED','DRAFT','SHIPPED'][i%5]
    if i in [14,28]:status='CANCELLED'
    cust=f'SYN26-C{customer_idx:02d}'
    prod=f'SYN26-P{model_idx:02d}'
    model,jp,en,mfr=products[model_idx-1]
    S.append(f"  SELECT customer_id INTO cid FROM m_customer WHERE customer_code={q(cust)};")
    S.append(f"  INSERT INTO t_order (order_no,customer_id,order_date,delivery_due_date,currency_code,total_amount,order_status,remarks) VALUES ({q(code)},cid,{q(dt)},{q(dt+timedelta(days=56))},'JPY',{total},{q(status)},'DEMO order - not a real sale') RETURNING order_id INTO oid;")
    S.append(f"  SELECT product_id INTO pid FROM m_product WHERE product_code={q(prod)};")
    S.append(f"  INSERT INTO t_order_detail(order_id,line_no,product_id,product_name,model_no,quantity,unit_code,unit_price,line_amount) VALUES(oid,1,pid,{q(jp)},{q(model)},{quantity},'SET',{price},{total});")
    records['orders'].append([code,dt.isoformat(),cust,model,total,status]);records['order_items'].append([code,model,quantity,price,total])
    order_meta.append((code,customer_idx,model_idx,status,dt))

# Exports: 72; ~every seventh includes two products. Mark all examples synthetic.
for i in range(1,73):
    code=f'SYN26-EXP-{i:04d}'
    exp_date=date(2024,5,8)+timedelta(days=i*9)
    order=None
    if i<=24:
        oi=(i%7)
        if order_meta[oi][3]!='CANCELLED' and order_meta[oi][4] <= exp_date:order=order_meta[oi]
    customer_idx=order[1] if order else (i*5+1)%len(customers)+1
    cust=f'SYN26-C{customer_idx:02d}'
    first_idx=order[2] if order else (i*5+3)%len(products)+1
    ids=[first_idx]
    if i%7==0:ids.append(((first_idx+8)%len(products))+1)
    dest,cc,port=(('Shanghai','CN','SHANGHAI') if i%4==0 else ('Busan','KR','BUSAN') if i%4==1 else ('Taipei','TW','KEELUNG') if i%4==2 else ('Hanoi','VN','HAIPHONG'))
    from_port=['YOKOHAMA','TOKYO','OSAKA','KOBE'][i%4]
    status=['EXPORTED','EXPORTED','APPROVED','DRAFT','EXPORTED','APPLYING'][i%6]
    is_monitored=(i<=40 and status=='EXPORTED')
    vessel=f'DEMO VESSEL {i%9+1:02d}'
    due=exp_date+timedelta(days=10)
    arrival=due+timedelta(days=(4+i%12))
    contract=f'SYN26-CT-{i:04d}'
    S.append(f'  SELECT customer_id INTO cid FROM m_customer WHERE customer_code={q(cust)};')
    if order:
        S.append(f'  SELECT order_id INTO oid FROM t_order WHERE order_no={q(order[0])};')
        order_sql='oid'
    else:order_sql='NULL'
    S.append(f"  INSERT INTO t_export(export_no,order_id,customer_id,contract_no,export_date,dest_country_code,destination_name,shipper_name,ship_from,ship_to,transport_mode,vessel,etd_date,eta_date,export_status,monitor_required_flg,remarks) VALUES({q(code)},{order_sql},cid,{q(contract)},{q(exp_date)},{q(cc)},{q('DEMO DESTINATION '+dest)},'DEMO SHIPPING CO.',{q(from_port)},{q(port)},'SEA',{q(vessel)},{q(due)},{q(arrival)},{q(status)},{q(is_monitored)},'SYNTHETIC TEST EXPORT - not an actual shipment') RETURNING export_id INTO eid;")
    val=0; packaging=[]
    line_num=0
    for j,product_idx in enumerate(ids,1):
        model,jp,en,mfr=products[product_idx-1]
        unitprice=1_700_000+product_idx*310_000
        val+=unitprice
        productcode=f'SYN26-P{product_idx:02d}'
        S.append(f'  SELECT product_id INTO pid FROM m_product WHERE product_code={q(productcode)};')
        S.append(f"  INSERT INTO t_export_item(export_id,line_no,product_id,equipment_name,model_no,serial_no,quantity,remarks) VALUES(eid,{j},pid,{q(jp)},{q(model)},{q('DEMO-SERIAL-'+str(i)+'-'+str(j))},1,'Demo-only export item') RETURNING export_item_id INTO eiid;")
        records['export_items'].append([code,j,model,jp,1])
        packages=['本体','電装盤','付属品','カバー']
        dims=[(2050+product_idx*31+i%5*30,1380+product_idx*14,1550+product_idx*24),(650,750,930),(780,630,890),(1150,740,360)]
        if product_idx%6==0:
            packages.append('制御部');dims.append((890,750,1160))
        for k,(part,dim) in enumerate(zip(packages,dims),1):
            line_num+=1
            volume=(Decimal(dim[0])*dim[1]*dim[2]/Decimal(10**9)).quantize(Decimal('0.001'),rounding=ROUND_HALF_UP)
            weight=[1250+product_idx*188+i%8*25,400+product_idx*12,180+product_idx*5,65+product_idx][(k-1)%4]
            if k==5:weight=355
            packaging.append((j,part,f'B{j}-{k}',*dim,weight,volume))
    inv_no=f'SYN26-INV-{i:04d}'
    pl_no=f'SYN26-PL-{i:04d}'
    S.append(f"  INSERT INTO t_invoice(export_id,invoice_no,invoice_date,contract_no,ship_from,ship_to,currency_code,total_quantity,total_unit,total_amount,to_party,delivery_end_user) VALUES(eid,{q(inv_no)},{q(exp_date)},{q(contract)},{q(from_port)},{q(port)},'JPY',{len(ids)},'SET',{val},'DEMO RECIPIENT','DEMO END USER') RETURNING invoice_id INTO iid;")
    records['invoices'].append([code,inv_no,exp_date.isoformat(),val,'JPY'])
    for j,product_idx in enumerate(ids,1):
        model,jp,en,mfr=products[product_idx-1]
        unitprice=1_700_000+product_idx*310_000
        S.append(f'  SELECT export_item_id INTO eiid FROM t_export_item WHERE export_id=eid AND line_no={j};')
        S.append(f"  INSERT INTO t_invoice_detail(export_id,invoice_id,line_no,export_item_id,item_name,model_no,quantity,unit_code,unit_price,line_amount) VALUES(eid,iid,{j},eiid,{q(jp)},{q(model)},1,'SET',{unitprice},{unitprice});")
        records.setdefault('invoice_details',[]).append([code,inv_no,j,model,unitprice])
    S.append(f"  INSERT INTO t_packing_list_header(export_id,invoice_id,packing_no,document_date,package_count_reported,total_gross_weight_kg_reported,total_volume_m3_reported,remarks) VALUES(eid,iid,{q(pl_no)},{q(exp_date)},{len(packaging)},{sum(z[6] for z in packaging)},{q(sum((z[7] for z in packaging),Decimal(0)))},'SYNTHETIC PACKING LIST') RETURNING packing_list_id INTO plid;")
    records['packing_headers'].append([code,pl_no,len(packaging),sum(z[6] for z in packaging),str(sum((z[7] for z in packaging),Decimal(0)))])
    for lineno,(j,part,partcode,l,w,h,weight,volume) in enumerate(packaging,1):
        S.append(f"  SELECT export_item_id INTO eiid FROM t_export_item WHERE export_id=eid AND line_no={j};")
        S.append(f"  INSERT INTO t_packing_list(export_id,packing_list_id,export_item_id,line_no,part_code,part_name,pcs,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3) VALUES(eid,plid,eiid,{lineno},{q(partcode)},{q(part)},1,{l},{w},{h},{weight},{q(volume)});")
        records['packing_lines'].append([code,pl_no,lineno,j,partcode,part,l,w,h,weight,str(volume)])
    if len(ids)>1 or i%13==0:
        ctype='40HQ'
    else:
        ctype=['20DRY','20DRY','40DRY','40HQ','20DRY'][i%5]
    qty=2 if len(ids)>1 or i%13==0 else 1
    basis=['DOCUMENTED','PLANNED','ACTUAL','DOCUMENTED','ACTUAL'][i%5]
    if status != 'EXPORTED' and basis == 'ACTUAL':basis='DOCUMENTED'
    if status == 'DRAFT':basis='PLANNED'
    source=f'SYN26 SIMULATED {basis} — NOT real shipment evidence'
    S.append(f"  INSERT INTO t_export_container(export_id,container_type,record_basis,container_qty,source_text,remarks) VALUES(eid,{q(ctype)},{q(basis)},{qty},{q(source)},'Synthetic configuration; no verified shipment');")
    records['containers'].append([code,ctype,qty,basis,source])
    if i%9==0: # mixed container types in single export
        S.append(f"  INSERT INTO t_export_container(export_id,container_type,record_basis,container_qty,source_text,remarks) VALUES(eid,{q('20DRY' if ctype!='20DRY' else '40HQ')},{q(basis)},1,{q(source)},'Synthetic mixed container scenario');")
        records['containers'].append([code,('20DRY' if ctype!='20DRY' else '40HQ'),1,basis,source])
    if is_monitored:
        S.append('  SELECT export_item_id INTO eiid FROM t_export_item WHERE export_id=eid AND line_no=1;')
        end=exp_date+timedelta(days=365*5)
        current=exp_date+timedelta(days=30+160*(2+i%2))
        mstatus=['MONITORING','SUSPENDED'][i%2]
        # scheduled upcoming next check is sample, not obligations
        S.append(f"  INSERT INTO t_export_monitor(export_id,export_item_id,monitor_start_date,monitor_end_date,monitor_status,last_check_date,next_check_date,remarks) VALUES(eid,eiid,{q(exp_date)},{q(end)},{q(mstatus)},{q(current)},{q(current+timedelta(days=180))},'DEMO ONLY — example workflow, not statutory monitoring') RETURNING monitor_id INTO mid;")
        records['monitor'].append([code,ids[0],exp_date.isoformat(),end.isoformat(),mstatus])
        for chk_idx in range(1,3+(i%2)):
            ckday=exp_date+timedelta(days=30+chk_idx*160)
            result=['OK','FOLLOW_UP','ISSUE'][((i//4)+chk_idx)%3]
            S.append(f"  INSERT INTO t_export_monitor_check(monitor_id,check_date,check_type,check_result,check_details,next_check_date) VALUES(mid,{q(ckday)},'SYNTHETIC_CHECK',{q(result)},'DEMO record only; not a performed inspection',{q(ckday+timedelta(days=160))});")
            records['monitor_checks'].append([code,ckday.isoformat(),result])
    records['exports'].append([code,inv_no,exp_date.isoformat(),cust,','.join(products[idx-1][0] for idx in ids),status,ctype,qty,basis])

# PDFs generated on disk and linked via UUID-format names; file refs never point to outside files.
DOCS=[]
for i in range(1,13):
    code=f'SYN26-EXP-{i:04d}'
    for dtype in ['INVOICE','PACKING_LIST']:
        docname=f'{code}_{dtype}_DEMO.pdf'
        storage=str(uuid.uuid5(uuid.NAMESPACE_URL,'demo:'+docname))+'.pdf'
        DOCS.append(('EXPORT',code,dtype,docname,storage,i))
for i in range(1,9):
    code=f'SYN26-SO-{i:04d}'
    docname=f'{code}_ORDER_DEMO.pdf'
    storage=str(uuid.uuid5(uuid.NAMESPACE_URL,'demo:'+docname))+'.pdf'
    DOCS.append(('ORDER',code,'ORDER',docname,storage,i))

# Use a clean synthetic watermark to ensure documents cannot be mistaken for real documents.
def make_pdf(owner,code,dtype,fn,storage,n):
    path=pdfs/storage
    c=canvas.Canvas(str(path),pagesize=A4,pageCompression=1)
    W,H=A4
    c.setTitle(f'SYNTHETIC DEMO {dtype} {code}')
    c.setFillColor(HexColor('#173B5C'));c.rect(0,H-105,W,105,fill=1,stroke=0)
    c.setFont('Helvetica-Bold',20);c.setFillColorRGB(1,1,1);c.drawString(36,H-54,dtype.replace('_',' '))
    c.setFont('Helvetica',10);c.drawString(36,H-75,'TRADE PLATFORM - GENERATED TEST SAMPLE')
    c.setFillColor(HexColor('#B51D22'))
    c.setFont('Helvetica-Bold',13);c.drawString(36,H-139,'SYNTHETIC DEMO - NOT A REAL BUSINESS DOCUMENT')
    fields=[('Reference',code),('Document type',dtype),('Test batch','SYN26'),('Document number',f'SYN26-INV-{n:04d}' if dtype=='INVOICE' else f'SYN26-PL-{n:04d}' if dtype=='PACKING_LIST' else code),('Currency','JPY'),('Data status','SIMULATED ONLY')]
    yy=H-191
    for title,value in fields:
        c.setFont('Helvetica-Bold',11);c.setFillColor(HexColor('#274A6A'));c.drawString(44,yy,title)
        c.setFont('Helvetica',11);c.setFillColor(HexColor('#142236'));c.drawString(190,yy,str(value));yy-=35
    c.setStrokeColor(HexColor('#B9CFDF'));c.line(37,yy+10,W-37,yy+10)
    yy-=40
    if owner=='EXPORT':
        exp=next(v for v in records['exports'] if v[0]==code)
        inv=next(v for v in records['invoices'] if v[0]==code)
        hdr=next(v for v in records['packing_headers'] if v[0]==code)
        cont=[v for v in records['containers'] if v[0]==code]
        related=[v for v in records['packing_lines'] if v[0]==code]
        if dtype=='PACKING_LIST':
            lines=[f"Equipment models: {exp[4]}",
                   f"Packing units: {hdr[2]}    Total weight: {hdr[3]:,} kg    Volume: {hdr[4]} m3"]
            parts_en=['Main body','Electrical cabinet','Accessories','Cover','Controller']
            for idx,item in enumerate(related[:9]):
                dims=f'{item[6]}x{item[7]}x{item[8]} mm'
                lines.append(f'{item[4]} / {parts_en[idx%len(parts_en)]} / {dims} / {item[9]:,} kg')
            lines += ['Container: '+', '.join(f'{it[1]} x {it[2]} ({it[3]})' for it in cont),
                      'Packing data are synthetic and are not shipment proof.']
        else:
            lines=[f"Models: {exp[4]}",f"Customer code: {exp[3]}",
                   f"Invoice issue date: {inv[2]}",f"Invoice amount: JPY {inv[3]:,}",
                   'No commercial value. This is not a legal invoice.']
    else:
        order=next(v for v in records['orders'] if v[0]==code)
        lines=[f"Model: {order[3]}",f"Order date: {order[1]}",
               f"Amount: JPY {order[4]:,}",f"Status: {order[5]}",
               'This sample does not represent a real purchase order.']
    for ln in lines:
        c.setFont('Helvetica',11);c.drawString(44,yy,ln);yy-=24
    c.setFillColor(HexColor('#B51D22'));c.setFont('Helvetica-Bold',10);c.drawString(35,70,'DO NOT USE FOR CUSTOMS, FINANCE OR SHIPPING')
    c.setFont('Helvetica',9);c.setFillColor(HexColor('#52677C'));c.drawString(35,50,'Fixture generated for the local application only.')
    c.showPage();c.save()
    return path.stat().st_size
for owner,code,dtype,name,storage,num in DOCS:
    size=make_pdf(owner,code,dtype,name,storage,num)
    if owner=='EXPORT':
        S.append(f"  SELECT export_id INTO eid FROM t_export WHERE export_no={q(code)};")
        S.append(f"  INSERT INTO t_document(export_id,document_type,file_name,storage_key,mime_type,file_size_bytes,remarks) VALUES(eid,{q(dtype)},{q(name)},{q(storage)},'application/pdf',{size},'DEMO generated PDF; NOT an original signed document');")
    else:
        S.append(f"  SELECT order_id INTO oid FROM t_order WHERE order_no={q(code)};")
        S.append(f"  INSERT INTO t_document(order_id,document_type,file_name,storage_key,mime_type,file_size_bytes,remarks) VALUES(oid,{q(dtype)},{q(name)},{q(storage)},'application/pdf',{size},'DEMO generated sales PDF; NOT a legal order');")
    records['documents'].append([code,dtype,name,storage,size])

S.append("  RAISE NOTICE 'SYN26 fixtures loaded successfully';\nEND\n$synseed$;\nCOMMIT;\n")
(root/'seed_demo.sql').write_text('\n'.join(S),encoding='utf-8')
for name, rows in records.items():
    if rows:
        with (root/f'{name}.csv').open('w',encoding='utf-8-sig',newline='') as f:
            wr=csv.writer(f); wr.writerow({
                'customers':['customer_code','customer_name','customer_name_en','country'],
                'products':['product_code','model_no','product_name','product_name_en','manufacturer'],
                'orders':['order_no','order_date','customer_code','model','total_amount_jpy','status'],
                'order_items':['order_no','model_no','qty','price_jpy','amount_jpy'],
                'exports':['export_no','invoice_no','export_date','customer_code','models','export_status','container_type','container_qty','record_basis'],
                'export_items':['export_no','line_no','model_no','equipment','quantity'],
                'invoices':['export_no','invoice_no','invoice_date','amount_jpy','currency'],
                'invoice_details':['export_no','invoice_no','line_no','model_no','unit_price_jpy'],
                'packing_headers':['export_no','packing_no','packages','gross_weight_kg','volume_m3'],
                'packing_lines':['export_no','packing_no','line_no','item_line_no','part_code','part_name','length_mm','width_mm','height_mm','gross_weight_kg','volume_m3'],
                'containers':['export_no','container_type','qty','record_basis','source_note'],
                'monitor':['export_no','product_index','start_date','end_date','status'],
                'monitor_checks':['export_no','check_date','result'],
                'documents':['owner_no','doc_type','filename','storage_key','size_bytes']
            }[name]);wr.writerows(rows)
(root/'manifest.json').write_text(json.dumps({'dataset':'SYN26', 'synthetic': True, 'files':{k:len(v) for k,v in records.items()},'pdf_files':len(DOCS),'reference_model':'SPG-X'},indent=2,ensure_ascii=False),encoding='utf-8')
print('generated', {k:len(v) for k,v in records.items()},'pdfs',len(DOCS),'SQL bytes',(root/'seed_demo.sql').stat().st_size)
