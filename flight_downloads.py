"""Disposable, authenticated flight-only exports. Never removes archive sources."""
import json
import math
import re
import secrets
import shutil
import subprocess
import threading
import time
from pathlib import Path
from hub_config import load_config


def flight_ranges(duration, skips):
    parts=[]
    for start,end in skips:
        start,end=float(start),float(end)
        if not math.isfinite(start) or not math.isfinite(end):raise ValueError('Invalid skip interval')
        start,end=max(0,min(duration,start)),max(0,min(duration,end))
        if end>start:parts.append([start,end])
    merged=[]
    for start,end in sorted(parts):
        if merged and start<=merged[-1][1]:merged[-1][1]=max(merged[-1][1],end)
        else:merged.append([start,end])
    keep=[];cursor=0
    for start,end in merged:
        if start>cursor:keep.append([cursor,start])
        cursor=end
    if cursor<duration:keep.append([cursor,duration])
    return keep


class FlightDownloads:
    def __init__(self,data,library):
        self.root=Path(data)/'flight-downloads';self.root.mkdir(parents=True,exist_ok=True)
        self.library=Path(library).resolve();self.lock=threading.RLock();self.encoder=threading.Lock();self.jobs={}
        self.cleanup()
        threading.Thread(target=self._janitor,daemon=True).start()

    def _remove(self,directory):
        directory=Path(directory)
        if directory.parent==self.root and re.fullmatch('[a-f0-9]{48}',directory.name):
            shutil.rmtree(directory,ignore_errors=True)

    def cleanup(self):
        ttl=load_config()['downloads']['temporary_ttl_seconds'];now=time.time()
        with self.lock:
            for token,job in list(self.jobs.items()):
                if job['state'] not in ('queued','rendering','delivering') and now-job['updated']>ttl:
                    self._remove(job['directory']);self.jobs.pop(token,None)
            known={job['directory'] for job in self.jobs.values()}
            for directory in self.root.iterdir():
                if directory not in known and directory.is_dir() and now-directory.stat().st_mtime>ttl:self._remove(directory)

    def _janitor(self):
        while True:
            time.sleep(60)
            try:self.cleanup()
            except Exception:pass

    def _public(self,job):
        result={key:job[key] for key in ('token','state','progress','name')}
        if job['state']=='ready':result['url']='/flight-download/'+job['token']
        if job.get('error'):result['error']=job['error']
        return result

    def status(self,token):
        with self.lock:
            if token not in self.jobs:raise KeyError('Download abgelaufen oder nicht gefunden')
            return self._public(self.jobs[token])

    def start(self,video):
        settings=load_config()['downloads'];source=Path(video['output']).resolve()
        if not source.is_relative_to(self.library) or not source.is_file():raise ValueError('Archivvideo fehlt')
        if video.get('flight_duration') is None:raise ValueError('Für dieses Video liegt keine freigegebene Fluganalyse vor')
        duration=float(video['duration']);keep=flight_ranges(duration,video['skip_intervals'])
        if not keep:raise ValueError('Keine Flugzeit zum Herunterladen erkannt')
        self.cleanup()
        with self.lock:
            for job in self.jobs.values():
                if job['id']==video['id'] and job['state'] in ('queued','rendering','ready'):return self._public(job)
            if sum(job['state'] in ('queued','rendering','ready','delivering') for job in self.jobs.values())>=settings['max_pending']:
                raise RuntimeError('Zu viele Downloads offen. Bitte einen Download abschließen oder später erneut versuchen.')
            needed=int(source.stat().st_size*sum(b-a for a,b in keep)/duration*2)+16*1024*1024
            if shutil.disk_usage(self.root).free<needed:raise RuntimeError('Zu wenig temporärer Speicher für den Download')
            token=secrets.token_hex(24);directory=self.root/token;directory.mkdir()
            name=re.sub(r'[^A-Za-z0-9._-]','_',source.stem)+'-flug.mp4'
            job={'token':token,'id':video['id'],'state':'queued','progress':0,'name':name,'directory':directory,'path':directory/'flight.mp4','updated':time.time()}
            self.jobs[token]=job
            threading.Thread(target=self._render,args=(job,source,keep,settings),daemon=True).start()
            return self._public(job)

    def _set(self,job,**values):
        with self.lock:job.update(values);job['updated']=time.time()

    def _render(self,job,source,keep,settings):
        try:
            with self.encoder:
                self._set(job,state='rendering')
                info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(source)],timeout=30))
                audio=any(stream.get('codec_type')=='audio' for stream in info['streams'])
                expected=sum(end-start for start,end in keep);count=len(keep);filters=[];seek=keep[0][0]
                if count>1:
                    filters.append('[0:v:0]split='+str(count)+''.join(f'[vin{i}]' for i in range(count)))
                    if audio:filters.append('[0:a:0]asplit='+str(count)+''.join(f'[ain{i}]' for i in range(count)))
                for i,(original_start,original_end) in enumerate(keep):
                    start,end=original_start-seek,original_end-seek
                    vin=f'[vin{i}]' if count>1 else '[0:v:0]'
                    filters.append(f'{vin}trim=start={start:.6f}:end={end:.6f},setpts=PTS-STARTPTS,format=yuv420p[v{i}]')
                    if audio:
                        ain=f'[ain{i}]' if count>1 else '[0:a:0]'
                        filters.append(f'{ain}atrim=start={start:.6f}:end={end:.6f},asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0,apad=whole_dur={end-start:.6f},atrim=duration={end-start:.6f}[a{i}]')
                vout='[v0]';aout='[a0]'
                if count>1:
                    inputs=''.join(f'[v{i}]'+(f'[a{i}]' if audio else '') for i in range(count))
                    filters.append(f'{inputs}concat=n={count}:v=1:a={int(audio)}[v]'+('[a]' if audio else ''))
                    vout='[v]';aout='[a]'
                command=['ffmpeg','-hide_banner','-v','error','-nostdin','-y','-ss',f'{seek:.6f}','-i',str(source),'-t',f'{keep[-1][1]-seek:.6f}','-filter_complex_threads','1','-filter_complex',';'.join(filters),'-map',vout]
                if audio:command+=['-map',aout,'-c:a','aac','-b:a','128k']
                command+=['-c:v','libx264','-threads',str(settings['threads']),'-preset',settings['preset'],'-crf',str(settings['crf']),'-pix_fmt','yuv420p','-profile:v','high','-level:v','4.0','-map_metadata','-1','-movflags','+faststart','-progress','pipe:1','-nostats',str(job['path'])]
                with (job['directory']/'ffmpeg.log').open('w+') as errors:
                    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=errors,text=True)
                    timer=threading.Timer(settings['encode_timeout_seconds'],process.kill);timer.daemon=True;timer.start()
                    try:
                        for line in process.stdout:
                            if line.startswith('out_time_us='):
                                try:self._set(job,progress=min(99,round(float(line.split('=',1)[1])/1e6/expected*100)))
                                except ValueError:pass
                        code=process.wait()
                    finally:
                        timer.cancel()
                        if process.poll() is None:process.kill();process.wait()
                    if code:
                        errors.seek(0);raise RuntimeError(errors.read()[-1000:] or 'Videoschnitt abgebrochen')
                actual=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(job['path'])],timeout=30).strip())
                if not job['path'].is_file() or job['path'].stat().st_size==0 or abs(actual-expected)>1:
                    raise RuntimeError('Geschnittene Datei hat eine unerwartete Dauer')
                (job['directory']/'ffmpeg.log').unlink(missing_ok=True)
                self._set(job,state='ready',progress=100,duration=actual)
        except Exception as error:
            self._remove(job['directory']);self._set(job,state='error',error=str(error)[:1000])

    def claim(self,token):
        with self.lock:
            job=self.jobs.get(token)
            if not job:raise KeyError('Download abgelaufen')
            if job['state']!='ready':raise ValueError('Download ist nicht bereit oder wurde bereits abgeholt')
            job['state']='delivering';job['updated']=time.time()
            return job['path'],job['name']

    def complete(self,token,success):
        with self.lock:
            job=self.jobs.get(token)
            if not job:return
            if success:
                self._remove(job['directory']);self._set(job,state='delivered')
            else:self._set(job,state='ready')
