"""Rebuild one named archive video's virtual cuts with the production detector."""
import json,sqlite3,sys
from pathlib import Path
import app

name=sys.argv[1]
with sqlite3.connect(app.DB) as c:
    rows=c.execute("SELECT id,output FROM files WHERE status='done' AND output IS NOT NULL").fetchall()
rows=[(fid,Path(output)) for fid,output in rows if (fid==int(name) if name.isdigit() else Path(output).name==name)]
if len(rows)!=1:raise RuntimeError(f'Expected exactly one archive entry: {rows}')
fid,path=rows[0]
app.analyse_flight(fid,path)
with sqlite3.connect(app.DB) as c:
    state,error=c.execute('SELECT cut_status,error FROM files WHERE id=?',(fid,)).fetchone()
if state!='done':raise RuntimeError(error)
data=json.loads(app.CUTLIST.read_text())
print(json.dumps({'file':str(path),'state':state,'intervals':data['videos'][str(path.relative_to(app.LIB))]}))
