#!/usr/bin/env python3
import argparse,base64,datetime,hashlib,json,math,mimetypes,os,shutil,signal,sqlite3,statistics,subprocess,tempfile,threading,time,urllib.parse
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import re
import fcntl
from collections import deque
from pathlib import Path
from hub_config import load_config, video_policy
from flight_downloads import FlightDownloads
ROOT=Path(__file__).resolve().parent; DATA=Path(os.environ.get('SDH_DATA',str(ROOT/'data'))); LIB=DATA/'library'; STAGE=DATA/'staging'; THUMBS=DATA/'thumbs'; DB=DATA/'state.sqlite3'; CUTLIST=LIB/'.flight-skip.json'; IMPORT_PAUSED=DATA/'.import-paused'; EXTS={'.mp4','.mov','.m4v','.avi','.mkv','.mts','.m2ts','.3gp','.webm','.ts'}; STOP=threading.Event(); LOCK=threading.RLock(); LIVE_LOCK=threading.Lock(); DEVICE_STATUS={}
DOWNLOADS=None
def downloads():
 global DOWNLOADS
 with LOCK:
  if DOWNLOADS is None:DOWNLOADS=FlightDownloads(DATA,LIB)
  return DOWNLOADS
def db():
 DATA.mkdir(parents=True,exist_ok=True); LIB.mkdir(exist_ok=True); STAGE.mkdir(exist_ok=True); THUMBS.mkdir(exist_ok=True); c=sqlite3.connect(DB); c.row_factory=sqlite3.Row; c.executescript('''CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,device TEXT,mount TEXT,label TEXT,status TEXT,created REAL,finished REAL,total_bytes INTEGER DEFAULT 0,copied_bytes INTEGER DEFAULT 0,compressed_bytes INTEGER DEFAULT 0,error TEXT); CREATE TABLE IF NOT EXISTS files(id INTEGER PRIMARY KEY,session TEXT,source TEXT,staging TEXT,output TEXT,thumb TEXT,name TEXT,size INTEGER,status TEXT,copy_progress REAL DEFAULT 0,compress_progress REAL DEFAULT 0,duration REAL DEFAULT 0,cut_progress REAL DEFAULT 0,cut_status TEXT DEFAULT 'pending',recorded_at REAL,recorded_source TEXT,error TEXT);''')
 for column,definition in (('duration','REAL DEFAULT 0'),('cut_progress','REAL DEFAULT 0'),('cut_status',"TEXT DEFAULT 'pending'"),('recorded_at','REAL'),('recorded_source','TEXT')):
  try:c.execute(f'ALTER TABLE files ADD COLUMN {column} {definition}')
  except sqlite3.OperationalError:pass
 c.commit(); return c
def run(a,timeout=60): return subprocess.run(a,text=True,capture_output=True,timeout=timeout,check=False)
def recording_time(path):
 try:
  r=run(['ffprobe','-v','error','-show_entries','format_tags=date,creation_time','-of','json',str(path)]); tags=json.loads(r.stdout or '{}').get('format',{}).get('tags',{})
  value=tags.get('date') or tags.get('creation_time')
  if value:
   parsed=datetime.datetime.fromisoformat(value.replace('Z','+00:00'))
   if parsed.tzinfo is None:parsed=parsed.replace(tzinfo=datetime.timezone.utc)
   return parsed.timestamp(),'embedded'
 except Exception:pass
 try:return path.stat().st_mtime,'filesystem'
 except Exception:return None,None
def live480_command(src):
 return ['ffmpeg','-hide_banner','-loglevel','error','-i',str(src),'-vf','scale=-2:480:flags=area,format=yuv420p','-c:v','h264_v4l2m2m','-b:v','2M','-maxrate','2500k','-bufsize','4M','-c:a','aac','-b:a','96k','-movflags','frag_keyframe+empty_moov+default_base_moof','-frag_duration','1000000','-f','mp4','pipe:1']
def lsblk():
 try:return json.loads(run(['lsblk','-J','-o','NAME,PATH,TYPE,RM,TRAN,LABEL,MOUNTPOINT,FSTYPE,SIZE,MODEL']).stdout).get('blockdevices',[])
 except Exception:return []
def parts(ns,removable=False):
 out=[]
 for n in ns:
  is_removable=removable or n.get('rm') is True or str(n.get('rm')).lower()=='true' or str(n.get('rm'))=='1' or n.get('tran')=='usb'
  if n.get('type')=='part' and is_removable:out.append(n)
  out+=parts(n.get('children') or [],is_removable)
 return out
def mount(p):
 if p.get('mountpoint'):
  DEVICE_STATUS[p['path']]={'path':p['path'],'size':p.get('size'),'state':'mounted','mount':p['mountpoint'],'error':''}; return p['mountpoint']
 r=run(['udisksctl','mount','-b',p['path']]);
 for x in r.stdout.splitlines():
  if ' at ' in x:
   mp=x.rsplit(' at ',1)[1].rstrip('.').strip(); DEVICE_STATUS[p['path']]={'path':p['path'],'size':p.get('size'),'state':'mounted','mount':mp,'error':''}; return mp
 DEVICE_STATUS[p['path']]={'path':p['path'],'size':p.get('size'),'state':'detected','mount':'','error':(r.stderr or 'mount failed').strip()[-300:]}; return None
def sid(dev,mp):return hashlib.sha256((dev+'|'+mp).encode()).hexdigest()[:16]
def reconcile_device_status(current_parts):
 active={p.get('path') for p in current_parts if p.get('path')}
 with LOCK:
  for path in list(DEVICE_STATUS):
   if path not in active:DEVICE_STATUS.pop(path,None)
def update(table,ident,**kw):
 with LOCK:
  c=db(); c.execute('UPDATE '+table+' SET '+','.join(k+'=?' for k in kw)+' WHERE id=?',tuple(kw.values())+(ident,)); c.commit(); c.close()
def snapshot():
 with LOCK:
  c=db(); s=[dict(x) for x in c.execute("SELECT * FROM sessions WHERE status!='done' ORDER BY created DESC").fetchall()]; f=[dict(x) for x in c.execute('SELECT * FROM files ORDER BY id').fetchall()]; labels={row['id']:row['label'] for row in c.execute('SELECT id,label FROM sessions')}; c.close()
 settings=load_config()
 try:cuts=json.loads(CUTLIST.read_text()).get('videos',{})
 except (OSError,ValueError):cuts={}
 for x in f:
  policy=video_policy(x['output'],labels.get(x['session'],''),settings)
  rel=str(Path(x['output']).relative_to(LIB)); ranges=cuts.get(rel)
  x['detection_enabled']=policy['enabled']; x['osd_font']=policy['font']; x['flight_duration']=None
  x['sound_enabled']=settings['playback']['sound_enabled'] and x['id'] not in settings['playback'].get('muted_video_ids',[])
  x['skip_intervals']=ranges if policy['enabled'] and ranges is not None else []
  if policy['enabled'] and x['cut_status']=='done' and ranges is not None:
   duration=float(x['duration'] or 0)
   clipped=merge_intervals([[max(0,min(duration,float(start))),max(0,min(duration,float(end)))] for start,end in ranges if end>start])
   x['flight_duration']=max(0,duration-sum(end-start for start,end in clipped))
  x['video_url']='/media/'+urllib.parse.quote(x['output'],safe=''); x['thumb_url']='/thumb/'+urllib.parse.quote(x['thumb'],safe='') if x['thumb'] else ''; x['output_size']=Path(x['output']).stat().st_size if Path(x['output']).is_file() else 0
 for x in s:
  own=[f for f in f if f['session']==x['id']]; x['video_bytes']=sum(int(v['size'] or 0) for v in own); x['compressed_bytes_live']=sum(int(v['output_size'] or 0) for v in own)
 return {'sessions':s,'files':f,'devices':list(DEVICE_STATUS.values()),'settings':settings}
def probe_duration(p):
 try:return float(run(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(p)]).stdout.strip())
 except Exception:return 0
def video_duration(p):
 try:return float(run(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=duration','-of','default=nw=1:nk=1',str(p)]).stdout.strip())
 except Exception:return 0
def merge_intervals(intervals):
 out=[]
 for start,end in sorted(intervals):
  if out and start<=out[-1][1]:out[-1][1]=max(out[-1][1],end)
  else:out.append([start,end])
 return out
def smooth_short_flights(intervals,duration,min_seconds=5):
 """Turn any flight gap shorter than min_seconds into virtual non-flight."""
 intervals=merge_intervals(intervals); cursor=0
 for start,end in list(intervals):
  if start-cursor<min_seconds:intervals.append([cursor,start])
  cursor=max(cursor,end)
 if duration-cursor<min_seconds:intervals.append([cursor,duration])
 return merge_intervals(intervals)
def bridge_persistent_hud(intervals,initial_seconds=10,max_gap_seconds=15):
 """Join OCR hits from one persistent menu/disarmed/stats screen."""
 intervals=merge_intervals(intervals)
 if intervals and intervals[0][0]<=initial_seconds:intervals[0][0]=0
 return merge_intervals([[start,end] for start,end in intervals] + [[left[1],right[0]] for left,right in zip(intervals,intervals[1:]) if right[0]-left[1]<=max_gap_seconds])
def readable_video(p):
 """A copied video can be hash-valid but still be an unfinished recorder file."""
 return probe_duration(p)>0
def make_thumb(src,dst):
 if not shutil.which('ffmpeg'):return False
 dst.parent.mkdir(parents=True,exist_ok=True)
 for seek in ('00:00:01','00:00:00'):
  r=run(['ffmpeg','-hide_banner','-loglevel','error','-y','-ss',seek,'-i',str(src),'-frames:v','1','-vf','scale=480:-2',str(dst)])
  if r.returncode==0 and dst.exists():return True
 return False
def analyse_flight(fid,out):
 """Create a virtual cut list; never alters or copies the MP4."""
 try:
  update('files',fid,cut_status='analysing',cut_progress=0)
  settings=load_config()
  with db() as connection:
   source=connection.execute('SELECT sessions.label FROM files JOIN sessions ON sessions.id=files.session WHERE files.id=?',(fid,)).fetchone()
  policy=video_policy(out,source['label'] if source else '',settings)
  enabled=policy['enabled']
  intervals=[]; duration=0; video_end=0
  if enabled:
   duration=probe_duration(out); video_end=video_duration(out)
   report=DATA/'template-reports'/f'{fid}.json'
   process=subprocess.Popen([str(ROOT/'.venv-template/bin/python'),str(ROOT/'hud_templates.py'),str(out),'--font',policy['font'],'--report',str(report)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
   result=None
   for line in process.stdout:
    event=json.loads(line)
    if 'progress' in event:update('files',fid,cut_progress=event['progress'])
    elif 'intervals' in event:result=event
   error=process.stderr.read()
   if process.wait()!=0 or result is None:raise RuntimeError(error[-1000:] or 'Template detection failed')
   intervals=result['intervals']
   if video_end and duration-video_end>=0.5:intervals.append([video_end,duration])
   intervals=smooth_short_flights(intervals,duration,settings['detection']['min_flight_seconds'])
  with LOCK, (DATA/'.flight-skip.lock').open('a') as cutlock:
   fcntl.flock(cutlock,fcntl.LOCK_EX)
   try:data=json.loads(CUTLIST.read_text())
   except Exception:data={'version':1,'algorithm':{},'videos':{}}
   data['algorithm']={'method':settings['detection']['classifier'],'config':'config.json','interval_seconds':settings['detection']['sample_seconds'],'min_flight_seconds':settings['detection']['min_flight_seconds']}
   data.setdefault('video_policies',{})[str(out.relative_to(LIB))]={**policy,'classifier':settings['detection']['classifier'],'detected_font':result.get('font','unknown') if enabled else None}
   data.setdefault('videos',{})[str(out.relative_to(LIB))]=intervals; tmp=CUTLIST.with_suffix('.tmp');tmp.write_text(json.dumps(data,indent=2)+'\n');tmp.replace(CUTLIST)
  update('files',fid,cut_status='done',cut_progress=100,error=None)
 except Exception as e:update('files',fid,cut_status='error',error=('cut analysis: '+str(e))[:1000])
def compress(fid,src,out,thumb):
 try:
  if not src.is_file():raise FileNotFoundError('staged source is missing; encoding not started: '+str(src))
  if src.stat().st_size==0:
   update('files',fid,status='skipped',compress_progress=100,cut_status='skipped',cut_progress=100,error='empty staged source; original retained'); return
  ffmpeg=shutil.which('ffmpeg')
  if not ffmpeg:raise RuntimeError('ffmpeg is unavailable; original retained in staging')
  out.parent.mkdir(parents=True,exist_ok=True); d=probe_duration(src); tmp_fd,tmp_name=tempfile.mkstemp(prefix=out.stem+'.',suffix='.partial.mp4',dir=out.parent); os.close(tmp_fd); tmp=Path(tmp_name)
  p=subprocess.Popen([ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(src),'-map_metadata','-1','-vf','scale=in_range=full:out_range=tv,format=yuv420p','-c:v','libx264','-preset','medium','-crf','28','-pix_fmt','yuv420p','-color_range','tv','-profile:v','high','-level:v','4.0','-c:a','aac','-b:a','128k','-movflags','+faststart','-progress','pipe:1','-nostats',str(tmp)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,errors='replace')
  stderr_tail=deque(maxlen=8)
  def drain_stderr():
   try:
    while True:
     chunk=p.stderr.read(4096)
     if not chunk:break
     stderr_tail.append(chunk)
   finally:p.stderr.close()
  stderr_reader=threading.Thread(target=drain_stderr,daemon=True); stderr_reader.start()
  while True:
   line=p.stdout.readline()
   if not line and p.poll() is not None:break
   if line.startswith('out_time_ms=') and math.isfinite(d) and d>0:
    try:update('files',fid,compress_progress=min(99.0,float(line.split('=',1)[1])/1e6/d*100))
    except ValueError:pass
  rc=p.wait(); stderr_reader.join(); err=''.join(stderr_tail)[-2000:].strip()
  if rc:
   diagnostic=err or 'ffmpeg failed'
   input_error=any(x in diagnostic.lower() for x in ('moov atom not found','invalid data found when processing input')) and not any(x in diagnostic.lower() for x in ('no space left on device','permission denied','cannot allocate memory','error opening output','error while opening encoder','error initializing output','error writing trailer'))
   if input_error:
    update('files',fid,status='skipped',compress_progress=100,cut_status='skipped',cut_progress=100,error=('ffmpeg input error; original retained in staging: '+diagnostic)[:1000]); return
   raise RuntimeError('ffmpeg failed; original retained in staging: '+diagnostic)
  duration=probe_duration(tmp)
  if not tmp.is_file() or tmp.stat().st_size<=0 or not math.isfinite(duration) or duration<=0:raise RuntimeError('ffmpeg produced invalid output; original retained in staging')
  tmp.replace(out)
  make_thumb(out,thumb); update('files',fid,status='done',compress_progress=100,duration=duration,error=None)
  analyse_flight(fid,out)
 except Exception as e:update('files',fid,status='error',error=str(e)[:1000])
def clear_and_compress(session):
 with LOCK:
  c=db(); s=c.execute('SELECT * FROM sessions WHERE id=?',(session,)).fetchone(); files=c.execute('SELECT * FROM files WHERE session=?',(session,)).fetchall(); c.close()
 if not s or s['status']!='awaiting_clear': return
 if IMPORT_PAUSED.exists():
  update('sessions',session,error='import paused; SD card was not cleared'); return
 if s['copied_bytes']!=s['total_bytes'] or any(not Path(f['staging']).is_file() or Path(f['staging']).stat().st_size!=Path(f['source']).stat().st_size for f in files):
  update('sessions',session,status='error',error='copy verification failed; SD card was not cleared'); return
 try:
  if IMPORT_PAUSED.exists():
   update('sessions',session,error='import paused; SD card was not cleared'); return
  mp=Path(s['mount']); [shutil.rmtree(x) if x.is_dir() else x.unlink() for x in mp.iterdir()]; r=run(['udisksctl','unmount','-b',s['device']])
  if r.returncode: r=run(['umount',str(mp)])
  if r.returncode: raise RuntimeError('unmount failed: '+r.stderr[-500:])
  update('sessions',session,status='compressing'); threading.Thread(target=compress_session,args=(session,),daemon=True).start()
 except Exception as e: update('sessions',session,status='error',error=str(e)[:1000])
def compress_session(session):
 c=db(); files=c.execute('SELECT * FROM files WHERE session=? ORDER BY id',(session,)).fetchall(); c.close(); update('sessions',session,status='compressing')
 for f in files:
  if STOP.is_set():return
  if f['status'] in ('done','skipped'):continue
  update('files',f['id'],status='compressing'); compress(f['id'],Path(f['staging']),Path(f['output']),Path(f['thumb']))
 with LOCK:
  c=db(); fresh=c.execute('SELECT * FROM files WHERE session=? ORDER BY id',(session,)).fetchall(); bad=any(f['status'] not in ('done','skipped') for f in fresh); skipped=[f for f in fresh if f['status']=='skipped']; safe_cleanup=bool(fresh) and all(f['status']=='done' and math.isfinite(float(f['duration'] or 0)) and float(f['duration'] or 0)>0 and Path(f['output']).is_file() and Path(f['output']).stat().st_size>0 for f in fresh); warning=('; '.join((f['error'] or ('skipped '+f['name'])) for f in skipped))[:1000] if skipped else None; c.execute('UPDATE sessions SET status=?,finished=?,error=? WHERE id=?',('error' if bad else 'done',time.time(),warning,session)); c.commit(); c.close()
 if safe_cleanup:
  stage_root=STAGE/session
  if stage_root.parent==STAGE and stage_root.is_dir():shutil.rmtree(stage_root)
def digest(path):
 h=hashlib.sha256()
 with open(path,'rb') as r:
  for chunk in iter(lambda:r.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
def copy_session(dev,mp):
 session=sid(dev,mp); label=''.join(c if c.isalnum() or c in '._-' else '_' for c in Path(mp).name)[:60]; base=STAGE/session; outbase=LIB/(time.strftime('%Y%m%d-%H%M%S')+'-'+label); c=db()
 if c.execute('SELECT id FROM sessions WHERE id=?',(session,)).fetchone():c.close();return
 entries=[p for p in Path(mp).rglob('*') if p.is_file()]; total=sum(p.stat().st_size for p in entries); c.execute('INSERT INTO sessions(id,device,mount,label,status,created,total_bytes) VALUES(?,?,?,?,?,?,?)',(session,dev,mp,label,'copying',time.time(),total)); c.commit()
 for src in entries:
  if STOP.is_set():break
  while IMPORT_PAUSED.exists():
   if STOP.wait(4):return
  rel=src.relative_to(mp); stage=base/rel; output=outbase/rel.with_suffix('.mp4') if src.suffix.lower() in EXTS else outbase/rel; thumb=THUMBS/session/(str(rel.with_suffix('.jpg')).replace('/','_')) if src.suffix.lower() in EXTS else ''
  stage.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,stage); size=src.stat().st_size
  if stage.stat().st_size!=size or (src.suffix.lower() in EXTS and digest(src)!=digest(stage)): raise IOError('copy verification failed for '+str(src))
  copied=c.execute('SELECT COALESCE(copied_bytes,0) v FROM sessions WHERE id=?',(session,)).fetchone()['v']+size; progress=copied/total*100 if total else 100
  if src.suffix.lower() in EXTS:
   recorded_at,recorded_source=recording_time(stage);c.execute('INSERT INTO files(session,source,staging,output,thumb,name,size,status,copy_progress,recorded_at,recorded_source) VALUES(?,?,?,?,?,?,?,?,?,?,?)',(session,str(src),str(stage),str(output),str(thumb),rel.name,size,'copied',100,recorded_at,recorded_source))
  c.execute('UPDATE sessions SET copied_bytes=? WHERE id=?',(copied,session)); c.commit()
 c.execute('UPDATE sessions SET status=? WHERE id=?',('awaiting_clear',session)); c.commit(); c.close(); threading.Thread(target=clear_and_compress,args=(session,),daemon=True).start()
def watcher():
 seen=set()
 while not STOP.wait(4):
  if IMPORT_PAUSED.exists():continue
  current_parts=parts(lsblk()); reconcile_device_status(current_parts)
  for p in current_parts:
   if not p.get('path') or p['path'] in seen:continue
   mp=mount(p)
   if mp:seen.add(p['path']);threading.Thread(target=copy_session,args=(p['path'],mp),daemon=True).start()
def auth(h):
 pw=os.environ.get('SDH_PASSWORD','')
 try:return bool(pw) and h.lower().startswith('basic ') and base64.b64decode(h.split(' ',1)[1]).decode().split(':',1)[1]==pw
 except Exception:return False
def index_bytes():
 source=(ROOT/'index.html').read_text()
 source=source.replace('__SDH_CUTLIST__',json.dumps(str(CUTLIST)))
 source=source.replace("const dur=s=>{s=Math.round(Number(s||0));return s?`${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`:'—'};", "const dur=s=>{s=Math.round(Number(s||0));return s?`${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`:'—'};const rec=f=>new Date(Number(f.recorded_at)*1000).toLocaleString('de-DE',{dateStyle:'medium',timeStyle:'short'});")
 source=source.replace("@media(max-width:600px)", ".crop43-thumbs .video.hdzero .thumb{aspect-ratio:4/3}.crop43-thumbs .video.hdzero .thumb img{object-fit:cover}@media(max-width:600px)")
 source=source.replace("comp=own.length?Math.round(own.reduce((a,f)=>a+Number(f.compress_progress||0),0)/own.length):0,processed=", "totalWork=own.reduce((a,f)=>a+Number(f.size||0),0),comp=totalWork?Math.round(own.reduce((a,f)=>a+Number(f.size||0)*Number(f.compress_progress||0),0)/totalWork):0,processed=")
 source=source.replace("x.sort((a,b)=>b.id-a.id);", "x.sort((a,b)=>{let ag=a.recorded_source==='embedded'?0:String(a.session).startsWith('pc-fpv-')?2:1,bg=b.recorded_source==='embedded'?0:String(b.session).startsWith('pc-fpv-')?2:1;if(ag!==bg)return ag-bg;if(ag===0)return Number(b.recorded_at||0)-Number(a.recorded_at||0);let an=Number((String(a.name).match(/\\d+/)||[0])[0]),bn=Number((String(b.name).match(/\\d+/)||[0])[0]);return bn-an||String(b.name).localeCompare(String(a.name),'de')});")
 source=source.replace("<article class=video>", "<article class=\"video ${f.recorded_source==='embedded'?'hdzero':''}\">")
 source=source.replace("function applyCrop(){viewer.classList.toggle('crop43',cropToggle.checked)}", "function applyCrop(){viewer.classList.toggle('crop43',cropToggle.checked);document.body.classList.toggle('crop43-thumbs',cropToggle.checked)}")
 source=source.replace("let rel=f.output.split('/library/')[1]||'',skips=skipMap[rel]||[],encoded=", "let rel=f.output.split('/library/')[1]||'',skips=f.recorded_source==='embedded'?(skipMap[rel]||[]):[],flight=Math.max(0,Number(f.duration||0)-skips.reduce((sum,x)=>sum+Math.max(0,Math.min(Number(f.duration||0),Number(x[1]))-Math.max(0,Number(x[0]))),0)),encoded=")
 source=source.replace("${dur(f.duration)} · ${fmt(f.output_size)} · MP4", "${f.recorded_source==='embedded'?`${dur(flight)} / ${dur(f.duration)} · ${rec(f)}`:dur(f.duration)} · ${fmt(f.output_size)}")
 return source.encode()
def offline_manifest():
 """Return only resources the first-party UI can request; no client paths are accepted."""
 entries=[]
 def generated(url,mime,body):entries.append({'url':url,'mime':mime,'size':len(body),'version':hashlib.sha256(body).hexdigest()})
 def backed(url,mime,path):
  try:
   stat=path.stat()
   if path.is_file():entries.append({'url':url,'mime':mime,'size':stat.st_size,'version':f'{stat.st_size}-{stat.st_mtime_ns}'})
  except OSError:pass
 generated('/','text/html; charset=utf-8',index_bytes())
 state=snapshot()
 generated('/api/state','application/json',json.dumps(state).encode())
 if CUTLIST.is_file():backed('/media/'+urllib.parse.quote(str(CUTLIST),safe=''),'application/json',CUTLIST)
 for item in state['files']:
  if item.get('status')!='done':continue
  output=Path(item.get('output','')).resolve()
  if LIB.resolve() in output.parents:backed(item['video_url'],mimetypes.guess_type(output.name)[0] or 'application/octet-stream',output)
  if item.get('thumb_url'):
   thumb=Path(item.get('thumb','')).resolve()
   if THUMBS.resolve() in thumb.parents:backed(item['thumb_url'],mimetypes.guess_type(thumb.name)[0] or 'application/octet-stream',thumb)
 return {'schema':1,'generated_at':int(time.time()),'entries':sorted(entries,key=lambda x:x['url'])}
class H(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def unauth(self):self.send_response(401);self.send_header('WWW-Authenticate','Basic realm="SD Video Hub"');self.end_headers()
 def js(self,x,code=200):
  b=json.dumps(x).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def index(self):
  source=(ROOT/'index.html').read_text()
  source=source.replace('__SDH_CUTLIST__',json.dumps(str(CUTLIST)))
  source=source.replace("const dur=s=>{s=Math.round(Number(s||0));return s?`${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`:'—'};", "const dur=s=>{s=Math.round(Number(s||0));return s?`${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`:'—'};const rec=f=>new Date(Number(f.recorded_at)*1000).toLocaleString('de-DE',{dateStyle:'medium',timeStyle:'short'});")
  source=source.replace("@media(max-width:600px)", ".crop43-thumbs .video.hdzero .thumb{aspect-ratio:4/3}.crop43-thumbs .video.hdzero .thumb img{object-fit:cover}@media(max-width:600px)")
  source=source.replace("comp=own.length?Math.round(own.reduce((a,f)=>a+Number(f.compress_progress||0),0)/own.length):0,processed=", "totalWork=own.reduce((a,f)=>a+Number(f.size||0),0),comp=totalWork?Math.round(own.reduce((a,f)=>a+Number(f.size||0)*Number(f.compress_progress||0),0)/totalWork):0,processed=")
  source=source.replace("x.sort((a,b)=>b.id-a.id);", "x.sort((a,b)=>{let ag=a.recorded_source==='embedded'?0:String(a.session).startsWith('pc-fpv-')?2:1,bg=b.recorded_source==='embedded'?0:String(b.session).startsWith('pc-fpv-')?2:1;if(ag!==bg)return ag-bg;if(ag===0)return Number(b.recorded_at||0)-Number(a.recorded_at||0);let an=Number((String(a.name).match(/\\d+/)||[0])[0]),bn=Number((String(b.name).match(/\\d+/)||[0])[0]);return bn-an||String(b.name).localeCompare(String(a.name),'de')});")
  source=source.replace("<article class=video>", "<article class=\"video ${f.recorded_source==='embedded'?'hdzero':''}\">")
  source=source.replace("function applyCrop(){viewer.classList.toggle('crop43',cropToggle.checked)}", "function applyCrop(){viewer.classList.toggle('crop43',cropToggle.checked);document.body.classList.toggle('crop43-thumbs',cropToggle.checked)}")
  source=source.replace("let rel=f.output.split('/library/')[1]||'',skips=skipMap[rel]||[],encoded=", "let rel=f.output.split('/library/')[1]||'',skips=f.recorded_source==='embedded'?(skipMap[rel]||[]):[],flight=Math.max(0,Number(f.duration||0)-skips.reduce((sum,x)=>sum+Math.max(0,Math.min(Number(f.duration||0),Number(x[1]))-Math.max(0,Number(x[0]))),0)),encoded=")
  source=source.replace("${dur(f.duration)} · ${fmt(f.output_size)} · MP4", "${f.recorded_source==='embedded'?`${dur(flight)} / ${dur(f.duration)} · ${rec(f)}`:dur(f.duration)} · ${fmt(f.output_size)}")
  b=source.encode();self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(b)));self.send_header('Cache-Control','no-store');self.end_headers()
  if self.command!='HEAD':self.wfile.write(b)
 def sendfile(self,p,typ,download=False):
  if not p.is_file():return self.js({'error':'not found'},404)
  size=p.stat().st_size; start=0; end=size-1; rh=self.headers.get('Range','')
  if rh.startswith('bytes='):
   try:
    a,b=rh[6:].split(',',1)[0].split('-',1)
    if not a and b: start=max(0,size-int(b)); end=size-1
    else: start=int(a); end=int(b) if b else size-1
    end=min(end,size-1)
    if start>end:raise ValueError
   except ValueError:
    self.send_response(416); self.send_header('Content-Range',f'bytes */{size}'); self.end_headers(); return
  length=end-start+1; self.send_response(206 if rh else 200); self.send_header('Content-Type',typ); self.send_header('Content-Length',str(length)); self.send_header('Accept-Ranges','bytes')
  if p==ROOT/'index.html':self.send_header('Cache-Control','no-store')
  if rh:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
  if download:self.send_header('Content-Disposition','attachment; filename="'+p.name+'"')
  self.end_headers()
  if self.command=='HEAD':return
  with open(p,'rb') as f:
   f.seek(start); remaining=length
   while remaining:
    chunk=f.read(min(1024*1024,remaining))
    if not chunk:break
    self.wfile.write(chunk); remaining-=len(chunk)
 def send_flight_download(self,token):
  manager=downloads()
  try:path,name=manager.claim(token)
  except KeyError as error:return self.js({'error':str(error)},404)
  except ValueError as error:return self.js({'error':str(error)},409)
  success=False
  try:
   size=path.stat().st_size
   self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(size));self.send_header('Content-Disposition','attachment; filename="'+name+'"');self.send_header('Cache-Control','no-store');self.send_header('Accept-Ranges','none');self.end_headers()
   with path.open('rb') as source:
    remaining=size
    while remaining:
     chunk=source.read(min(1024*1024,remaining))
     if not chunk:break
     self.wfile.write(chunk);remaining-=len(chunk)
   self.wfile.flush();success=remaining==0
  except (BrokenPipeError,ConnectionResetError,OSError):pass
  finally:manager.complete(token,success)
 def send_live480(self,p):
  if not p.is_file():return self.js({'error':'not found'},404)
  if not LIVE_LOCK.acquire(blocking=False):return self.js({'error':'Daten-sparen-Stream ist bereits aktiv'},429)
  proc=None
  try:
   proc=subprocess.Popen(live480_command(p),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0)
   self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers();self.close_connection=True
   while True:
    chunk=proc.stdout.read(65536)
    if not chunk:break
    self.wfile.write(chunk);self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):pass
  finally:
   if proc and proc.poll() is None:proc.terminate()
   if proc:
    try:proc.wait(timeout=3)
    except subprocess.TimeoutExpired:proc.kill();proc.wait()
   LIVE_LOCK.release()
 def do_GET(self):
  if not auth(self.headers.get('Authorization','')):return self.unauth()
  u=urllib.parse.urlparse(self.path)
  if u.path=='/api/state':return self.js(snapshot())
  if u.path=='/api/offline-manifest':return self.js(offline_manifest())
  if u.path.startswith('/api/flight-download/'):
   try:return self.js(downloads().status(u.path.rsplit('/',1)[1]))
   except KeyError as error:return self.js({'error':str(error)},404)
  if u.path.startswith('/flight-download/'):return self.send_flight_download(u.path.rsplit('/',1)[1])
  if u.path=='/':return self.index()
  if u.path.startswith('/stream480/'):
   p=Path(urllib.parse.unquote(u.path.split('/',2)[2])).resolve()
   if LIB.resolve() not in p.parents:return self.js({'error':'bad path'},400)
   return self.send_live480(p)
  if u.path.startswith('/media/') or u.path.startswith('/thumb/'):
   base=LIB if u.path.startswith('/media/') else THUMBS; p=Path(urllib.parse.unquote(u.path.split('/',2)[2])).resolve()
   if base.resolve() not in p.parents:return self.js({'error':'bad path'},400)
   return self.sendfile(p,mimetypes.guess_type(p.name)[0] or 'application/octet-stream',u.query=='download')
  return self.js({'error':'not found'},404)
 def do_POST(self):
  if not auth(self.headers.get('Authorization','')):return self.unauth()
  if self.path=='/api/flight-download':
   if self.headers.get('Content-Type','').split(';')[0]!='application/json':return self.js({'error':'JSON required'},415)
   origin=self.headers.get('Origin')
   if origin and origin not in ('http://'+self.headers.get('Host',''),'https://'+self.headers.get('Host','')):return self.js({'error':'Origin not allowed'},403)
   try:
    length=int(self.headers.get('Content-Length','0'))
    if not 0<length<=4096:raise ValueError('Invalid request size')
    payload=json.loads(self.rfile.read(length))
    if type(payload.get('id')) is not int:raise ValueError('Video-ID fehlt')
    video=next((f for f in snapshot()['files'] if f['id']==payload['id'] and f['status']=='done'),None)
    if video is None:return self.js({'error':'Video nicht gefunden'},404)
    return self.js(downloads().start(video),202)
   except (ValueError,TypeError) as error:return self.js({'error':str(error)},409)
   except RuntimeError as error:return self.js({'error':str(error)},429)
  if self.path!='/api/clear':return self.js({'error':'not found'},404)
  n=int(self.headers.get('Content-Length','0'));d=json.loads(self.rfile.read(n) or b'{}'); session=d.get('session');c=db();s=c.execute('SELECT * FROM sessions WHERE id=?',(session,)).fetchone();fs=c.execute('SELECT status FROM files WHERE session=?',(session,)).fetchall();c.close()
  if not s:return self.js({'error':'unknown session'},404)
  if IMPORT_PAUSED.exists():return self.js({'error':'import paused; SD card was not cleared'},409)
  if s['status']!='awaiting_clear' or any(x['status']!='copied' for x in fs):return self.js({'error':'copy phase is not complete'},409)
  if d.get('confirm')!='CLEAR '+session:return self.js({'error':'exact confirmation required'},400)
  try:
   if IMPORT_PAUSED.exists():return self.js({'error':'import paused; SD card was not cleared'},409)
   mp=Path(s['mount']);[shutil.rmtree(x) if x.is_dir() else x.unlink() for x in mp.iterdir()];r=run(['udisksctl','unmount','-b',s['device']]);
   if r.returncode:return self.js({'error':'unmount failed: '+r.stderr[-500:]},500)
   c=db();c.execute('UPDATE sessions SET status=? WHERE id=?',('compressing',session));c.commit();c.close();threading.Thread(target=compress_session,args=(session,),daemon=True).start();return self.js({'ok':True})
  except Exception as e:return self.js({'error':str(e)},500)
def main():
 a=argparse.ArgumentParser();a.add_argument('--host',default='127.0.0.1');a.add_argument('--port',type=int,default=8787);x=a.parse_args()
 if not os.environ.get('SDH_PASSWORD'):raise SystemExit('SDH_PASSWORD is required')
 db();
 c=db(); recovering=c.execute("SELECT id,status FROM sessions WHERE status IN ('compressing','awaiting_clear')").fetchall(); c.close()
 for row in recovering:
  threading.Thread(target=clear_and_compress if row['status']=='awaiting_clear' else compress_session,args=(row['id'],),daemon=True).start()
 threading.Thread(target=watcher,daemon=True).start();srv=ThreadingHTTPServer((x.host,x.port),H)
 def stop(*_):STOP.set();threading.Thread(target=srv.shutdown,daemon=True).start()
 signal.signal(signal.SIGTERM,stop);srv.serve_forever()
if __name__=='__main__':main()
