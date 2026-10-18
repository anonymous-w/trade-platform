#!/usr/bin/env python3
"""Read-only management search regression on the isolated localhost:8089 test stack.
Use a restored copy containing packing source rows; TEST_* and safety guard are required.
"""
import os,urllib.parse
from http_regression import Client,check,equal

def main():
    reader=Client();reader.login(os.environ['TEST_READER']);anon=Client()
    check('anonymous source search denied',lambda:anon.call('/packing-sources',status=401))
    first=reader.call('/packing-sources?size=100');total=first['total'];assert total>0
    rows=[]
    for page in range((total+99)//100):rows+=reader.call(f'/packing-sources?size=100&page={page}')['rows']
    check('all source rows accessible to reader',lambda:equal(len(rows),total))
    check('source IDs unique across batches',lambda:equal(len({r['sourceId'] for r in rows}),total))
    check('source metadata included',lambda:equal(all(r['fileName'] and r['batchId'] and r['importedAt'] for r in rows),True))
    check('stable pagination ordering',lambda:equal([(r['batchId'],r['sourceRow']) for r in rows],sorted([(r['batchId'],r['sourceRow']) for r in rows],key=lambda t:(-t[0],t[1]))))
    keys=['contractNo','equipmentName','modelNo','partName','partCode','containerText','departurePort','destinationPort']
    def search(params):return reader.call('/packing-sources?'+urllib.parse.urlencode(params))
    def matching(q):return [r for r in rows if any(q.casefold() in str(r.get(k) or '').casefold() for k in keys)]
    sample=next(r for r in rows if r['contractNo'] and r['modelNo'] and r['containerText'] and r['departurePort'])
    for field in ['contractNo','modelNo','partName','containerText','departurePort','destinationPort']:
        q=sample[field].strip();expected=len(matching(q))
        check('keyword searches '+field,lambda q=q,e=expected:equal(search({'keyword':q})['total'],e))
    check('case-insensitive and trimmed keyword',lambda:equal(search({'keyword':'  '+sample['contractNo'].lower()+'  '})['total'],len(matching(sample['contractNo'].lower()))))
    for parameter,field in [('model','modelNo'),('container','containerText'),('departurePort','departurePort'),('destinationPort','destinationPort')]:
        value=sample[field].strip();expected=sum(value.casefold() in str(r.get(field) or '').casefold() for r in rows)
        check('field filter '+parameter,lambda p=parameter,v=value,e=expected:equal(search({p:v})['total'],e))
    params={'model':sample['modelNo'].strip(),'departurePort':sample['departurePort'].strip()}
    expected=sum(params['model'].casefold() in str(r['modelNo']).casefold() and params['departurePort'].casefold() in str(r['departurePort']).casefold() for r in rows)
    check('combined filters use AND',lambda:equal(search(params)['total'],expected))
    check('review filter',lambda:equal(search({'reviewOnly':'true'})['total'],sum(bool(r['warnings']) for r in rows)))
    for literal in ['%','_',"' OR 1=1 --"]:
        check('literal wildcard or SQL text '+repr(literal),lambda q=literal:equal(search({'keyword':q})['total'],len(matching(q))))
    for query in ['page=-1','size=0','size=101','page=abc','keyword='+('x'*201),'container='+('x'*201)]:
        check('invalid query rejected',lambda q=query:reader.call('/packing-sources?'+q,status=400))
    check('empty result page',lambda:equal(search({'page':100000})['rows'],[]))
    reader.call('/auth/logout','POST');reader.token=''
    check('logout closes source search access',lambda:reader.call('/packing-sources',status=401))
    print('MANAGEMENT SEARCH REGRESSION PASSED')
if __name__=='__main__':main()
