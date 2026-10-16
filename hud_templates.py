#!/usr/bin/env python3
"""One shared MAX7456 template classifier; all policy/settings live in config.json."""
import argparse,json,subprocess,time
from collections import Counter
from pathlib import Path
import cv2
import numpy as np
from hub_config import ROOT,load_config,video_policy
from osd_matcher import TemplateMatcher,read_font,render


def bank(font_name,analog,settings):
    """Template preparation compatibility helper; not a separate classifier."""
    glyphs=read_font(ROOT/settings['fonts'][font_name]['file']);pitch=16 if analog else 12
    return [(word.lower(),render(glyphs,word,pitch,pitch,height)['white']) for word in settings['detection']['words'] for height in (16,17)]


def near(a,b,options):
    return a['word']==b['word'] and a['font']==b['font'] and max(abs(x-y) for x,y in zip(a['xy'],b['xy']))<=options['position_tolerance_pixels']


def smooth(samples,duration,settings):
    options=settings['detection'];step=options['sample_seconds']
    candidates=[sample for sample in samples if sample['support']]
    strong=[hit for hit in candidates if hit['score']>=options['strong_score']]
    confirmed=[hit for hit in strong if hit['score']>=options['single_hit_score'] or any(other is not hit and abs(other['second']-hit['second'])<=options['confirmation_seconds'] and other['score']>=options['weak_score'] and near(hit,other,options) for other in candidates)]
    accepted=[hit for hit in candidates if hit['score']>=options['weak_score'] and any(abs(anchor['second']-hit['second'])<=options['support_seconds'] and near(anchor,hit,options) for anchor in confirmed)]
    parts=[]
    for hit in accepted:
        start=max(0,hit['second']-step/2);end=min(duration,hit['second']+step/2)
        if parts and start-parts[-1][1]<options['min_flight_seconds']:parts[-1][1]=max(parts[-1][1],end)
        else:parts.append([start,end])
    if parts and parts[0][0]<options['min_flight_seconds']:parts[0][0]=0
    if parts and duration-parts[-1][1]<options['min_flight_seconds']:parts[-1][1]=duration
    return parts,accepted


def analyse(video,limit=None,font_name=None):
    settings=load_config();options=settings['detection'];policy=video_policy(video,settings=settings)
    hint=font_name or policy['font']
    if not policy['enabled'] or (hint!='auto' and not settings['fonts'][hint]['detection_enabled']):
        raise ValueError(f'Detection disabled by config.json for {video} / {hint}')
    files={name:ROOT/font['file'] for name,font in settings['fonts'].items() if font['detection_enabled']}
    matcher=TemplateMatcher(files,words=options['words'],strong=options['strong_score'],weak=options['weak_score'],candidate_floor=options['candidate_floor'],peaks=options['candidate_peaks'],coarse_size=(options['frame_width'],options['frame_height']),letter_options=options)
    matcher.preferred_font=hint if hint in files else None
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=width,height:format=duration','-of','json',str(video)]))
    stream=next(s for s in info['streams'] if 'width' in s);width,height=stream['width'],stream['height']
    duration=float(info['format']['duration']);duration=min(duration,limit) if limit else duration
    step=options['sample_seconds']
    # Preserve native pixels for refinement; resizing here destroyed thin legacy strokes.
    command=['ffmpeg','-v','error','-i',str(video),'-t',str(duration),'-vf',f'fps=fps=1/{step}:start_time=0:round=up,format=gray','-f','rawvideo','-pix_fmt','gray','pipe:1']
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    samples=[];size=width*height;started=time.monotonic()
    try:
        while True:
            raw=bytearray()
            while len(raw)<size:
                chunk=process.stdout.read(size-len(raw))
                if not chunk:break
                raw.extend(chunk)
            if not raw:break
            if len(raw)!=size:raise RuntimeError('Truncated video frame')
            prediction=matcher.inspect(np.frombuffer(raw,np.uint8).reshape(height,width));best=prediction['best']
            second=len(samples)*step
            samples.append({'second':second,'state':prediction['state'],'word':best['word'] if best else '', 'font':best['font'] if best else '', 'score':best['score'] if best else 0, 'xy':best['xy'] if best else [0,0], 'support':bool(best and best['support']), 'matches':prediction['matches']})
            if len(samples)%10==0:print(json.dumps({'progress':min(99,second/duration*100)}),flush=True)
        if process.wait()!=0:raise RuntimeError('Video decoding failed')
    finally:
        if process.poll() is None:process.terminate();process.wait()
    intervals,accepted=smooth(samples,duration,settings)
    fonts=Counter(hit['font'] for hit in accepted)
    return {'intervals':intervals,'samples':samples,'accepted':accepted,'wall_seconds':round(time.monotonic()-started,3),'duration':duration,'method':options['classifier'],'font':fonts.most_common(1)[0][0] if fonts else 'unknown','detected_fonts':dict(fonts)}


def prepare_font(font_name):
    settings=load_config();destination=ROOT/'assets/templates'/font_name;destination.mkdir(parents=True,exist_ok=True);words={}
    glyphs=read_font(ROOT/settings['fonts'][font_name]['file'])
    for word in settings['detection']['words']:
        template=render(glyphs,word,12,12,18)['white'];path=destination/(word.lower()+'.png')
        if not cv2.imwrite(str(path),(template*255).astype(np.uint8)):raise RuntimeError('Template write failed')
        words[word.lower()]=str(path.relative_to(ROOT))
    return {'font':font_name,'detection_enabled':settings['fonts'][font_name]['detection_enabled'],'templates':words}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('video',nargs='?',type=Path);p.add_argument('--font');p.add_argument('--prepare-font');p.add_argument('--report',type=Path);p.add_argument('--limit',type=float);args=p.parse_args()
    if args.prepare_font:print(json.dumps(prepare_font(args.prepare_font)));raise SystemExit(0)
    if not args.video:p.error('video is required unless --prepare-font is used')
    result=analyse(args.video,args.limit,args.font)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({key:result[key] for key in ('intervals','wall_seconds','duration','method','font','detected_fonts')}),flush=True)
