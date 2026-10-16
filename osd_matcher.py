"""One position-independent coarse-to-fine classifier for all MAX7456 font banks."""
from pathlib import Path
import math
import cv2
import numpy as np


def read_font(path):
    lines=Path(path).read_text().splitlines()
    if not lines or lines[0]!='MAX7456' or len(lines)-1!=256*64:raise ValueError('Invalid MAX7456 font file')
    bits=np.array([[int(line[i:i+2],2) for i in (0,2,4,6)] for line in lines[1:]],np.uint8)
    return bits.reshape(256,256)[:,:216].reshape(256,18,12)


def render(glyphs,word,pitch,width,height):
    shape=(height,round(pitch*len(word)));alpha=np.zeros(shape,np.float32);premult=np.zeros(shape,np.float32)
    for i,char in enumerate(word):
        raw=glyphs[ord(char)];a=((raw==0)|(raw==2)).astype(np.float32);white=(raw==2).astype(np.float32)
        ar=cv2.resize(a,(width,height));wr=cv2.resize(white,(width,height));x=round(i*pitch+(pitch-width)/2)
        alpha[:,x:x+width]=ar;premult[:,x:x+width]=wr
    yy,xx=np.where(alpha>.01);y0=max(0,yy.min()-1);x0=max(0,xx.min()-1)
    crop=(slice(y0,min(height,yy.max()+2)),slice(x0,xx.max()+2));a=alpha[crop];p=premult[crop]
    value=np.divide(p,a,out=np.zeros_like(a),where=a>1e-5)
    white_y,white_x=np.where(p>.01)
    white=p[max(0,white_y.min()-1):min(p.shape[0],white_y.max()+2),max(0,white_x.min()-1):white_x.max()+2]
    letters=[(max(0,round(i*pitch)-x0),min(a.shape[1],round((i+1)*pitch)-x0)) for i in range(len(word))]
    return {'value':value,'mask':a,'white':white,'letters':letters}


def letter_support(patch,template):
    result=[]
    for start,end in template['letters']:
        mask=template['mask'][:,start:end];weight=mask*mask;total=float(weight.sum())
        if total<1:result.append(0.);continue
        want=template['value'][:,start:end];got=patch[:,start:end]
        want=want-float((want*weight).sum())/total;got=got-float((got*weight).sum())/total
        denom=math.sqrt(float((weight*want*want).sum())*float((weight*got*got).sum()))
        result.append(float((weight*want*got).sum())/denom if denom>1e-10 else 0.)
    return result


class TemplateMatcher:
    def __init__(self,font_files,words=('DISARMED','MENU','STATS'),strong=.78,weak=.70,candidate_floor=.40,peaks=8,coarse_size=(480,270),letter_options=None):
        cv2.setNumThreads(2)
        self.fonts={name:read_font(path) for name,path in font_files.items()};self.words=tuple(words)
        self.strong=strong;self.weak=weak;self.candidate_floor=candidate_floor;self.peaks=peaks
        self.cw,self.ch=coarse_size
        self.letter_options=letter_options or {'letter_good_score':.45,'letter_good_fraction':.75,'letter_min_score':.15,'letter_mean_score':.65}
        self.banks={};self.native_templates={};self.preferred_font=None

    def _bank(self,w,h):
        analog=w/h<1.6
        pitch=self.cw/30 if analog else self.cw/40
        # Analog MAX7456 cells keep the same native size in PAL and NTSC.
        cell_height=(w/20)*self.ch/h if analog else self.ch/16
        heights=sorted({math.floor(cell_height),math.ceil(cell_height)})
        key=(pitch,tuple(heights))
        if key not in self.banks:
            bank=[]
            for name,glyphs in self.fonts.items():
                for word in self.words:
                    for height in heights:
                        bank.append((name,word,height,render(glyphs,word,pitch,round(pitch),height)['white']))
            self.banks[key]=bank
        return pitch,self.banks[key]

    def inspect(self,image):
        native=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY).astype(np.float32)/255 if image.ndim==3 else image.astype(np.float32)/255
        h,w=native.shape;sx,sy=w/self.cw,h/self.ch;pitch,bank=self._bank(w,h)
        small=cv2.resize(native,(self.cw,self.ch));field=np.maximum(0,small-cv2.GaussianBlur(small,(0,0),1.5));matches={}
        ordered=sorted(bank,key=lambda item:item[0]!=self.preferred_font) if self.preferred_font else bank
        for name,word,height,coarse_template in ordered:
            coarse_result=cv2.matchTemplate(field,coarse_template,cv2.TM_CCOEFF_NORMED)
            for peak in range(self.peaks):
                _,coarse,_,xy=cv2.minMaxLoc(coarse_result)
                if coarse<self.candidate_floor:break
                rh,rw=coarse_template.shape
                coarse_result[max(0,xy[1]-rh//2):xy[1]+rh//2+1,max(0,xy[0]-rw//2):xy[0]+rw//2+1]=-1
                for height_native in {round(height*sy),math.ceil(height*sy)}:
                    key=(name,word,pitch,w,h,height_native)
                    if key not in self.native_templates:
                        self.native_templates[key]=render(self.fonts[name],word,pitch*sx,round(pitch*sx),height_native)
                    template=self.native_templates[key];t=template['value'];mask=template['mask']
                    radius=max(8,round(max(sx,sy)*4));x0=max(0,round(xy[0]*sx)-radius);y0=max(0,round(xy[1]*sy)-radius)
                    x1=min(w,round(xy[0]*sx)+t.shape[1]+radius);y1=min(h,round(xy[1]*sy)+t.shape[0]+radius);roi=native[y0:y1,x0:x1]
                    if roi.shape[0]<t.shape[0] or roi.shape[1]<t.shape[1]:continue
                    corr=cv2.matchTemplate(roi,t,cv2.TM_CCOEFF_NORMED,mask=mask);corr=np.nan_to_num(corr,nan=-1,posinf=-1,neginf=-1)
                    _,score,_,point=cv2.minMaxLoc(corr);identifier=name+'/'+word.lower()
                    patch=roi[point[1]:point[1]+t.shape[0],point[0]:point[0]+t.shape[1]];letters=letter_support(patch,template)
                    opts=self.letter_options
                    support=sum(v>=opts['letter_good_score'] for v in letters)>=math.ceil(len(word)*opts['letter_good_fraction']) and min(letters)>opts['letter_min_score'] and sum(letters)/len(letters)>=opts['letter_mean_score']
                    previous=matches.get(identifier)
                    if previous and (previous['support'],previous['score'])>=(support,score):continue
                    matches[identifier]={'word':word.lower(),'font':name,'score':round(score,4),'coarse_score':round(coarse,4),'xy':[round((point[0]+x0)/sx,2),round((point[1]+y0)/sy,2)],'native_xy':[point[0]+x0,point[1]+y0],'letters':[round(v,4) for v in letters],'support':support}
                    if support and score>=self.strong:
                        self.preferred_font=name
                        return {'state':word.lower(),'best':matches[identifier],'matches':matches}
        ranked=sorted(matches.values(),key=lambda m:m['score'],reverse=True)
        supported=[match for match in ranked if match['support']]
        best=supported[0] if supported else (ranked[0] if ranked else None)
        state=best['word'] if best and best['score']>=self.strong and best['support'] else 'flight'
        return {'state':state,'best':best,'matches':matches}
