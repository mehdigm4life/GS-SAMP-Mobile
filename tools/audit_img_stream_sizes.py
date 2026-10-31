"""Read only bounded IMG directory prefixes, never full multi-GB archives."""
import json, struct, urllib.request, urllib.parse

def open_url(url):
    if urllib.parse.urlparse(url).scheme != 'https': raise ValueError('HTTPS required')
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'GS-SAMP-audit','Range':'bytes=0-4194303'}), timeout=60)
base='https://samp-mobile.shop/files.json'
with open_url(base) as r: manifest=json.loads(r.read(4_194_304))
files=manifest if isinstance(manifest,list) else manifest.get('files',manifest.get('data',[]))
for f in files:
    path=f.get('path', f.get('name',''))
    if not path.lower().endswith('.img'): continue
    url=urllib.parse.urljoin(base,f.get('url', f.get('download_url',path)))
    with open_url(url) as r:
        header=r.read(8)
        if len(header)!=8 or header[:4]!=b'VER2': raise ValueError(f'{path}: not IMG v2')
        count=struct.unpack_from('<I',header,4)[0]
        if count>100_000:raise ValueError('directory count exceeds audit limit')
        entries=r.read(count*32)
        if len(entries)!=count*32:raise ValueError('short directory')
    rows=[]
    for i in range(count):
        pos,first,second,name=struct.unpack_from('<IHH24s',entries,i*32)
        rows.append((first,second or first,name.split(b'\0')[0].decode('ascii','replace')))
    old=max(x[0] for x in rows); effective=max(x[1] for x in rows)
    largest=sorted(rows,key=lambda x:x[1],reverse=True)[:4]
    message=f'{path}: entries={count}, original max={old}, effective read max={effective}, largest={largest}'
    print('::notice::'+message.replace('%','%25').replace('\n','%0A'))
