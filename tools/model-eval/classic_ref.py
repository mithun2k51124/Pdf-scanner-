import cv2, numpy as np, sys
import det2, det5
from det2 import order
from det4 import support
def detect(gray, verbose=False):
    h,w=gray.shape; s=480/max(h,w); sm=cv2.resize(gray,(int(w*s),int(h*s)),interpolation=cv2.INTER_AREA)
    det2.H,det2.W=sm.shape; fa=sm.size; minA=0.12*fa
    b=cv2.GaussianBlur(cv2.medianBlur(sm,7),(5,5),0)
    bins=[]
    for lo,hi in ((20,60),(40,120),(70,180)):
        e=cv2.Canny(b,lo,hi); bins.append(cv2.dilate(e,np.ones((3,3),np.uint8)))
    sup=cv2.dilate(cv2.Canny(b,30,90),np.ones((9,9),np.uint8))
    _,t=cv2.threshold(b,0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU); t=cv2.morphologyEx(t,cv2.MORPH_CLOSE,np.ones((9,9),np.uint8)); bins.append(t)
    cs_all=[]
    for bi in bins:
        cs,_=cv2.findContours(bi,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
        for c in [c for c in sorted(cs,key=cv2.contourArea,reverse=True)[:10] if cv2.contourArea(c)>=minA]:
            r=det5.cand(c,minA,fa)
            if r: cs_all.append((r[0],r[1],support(r[0],sup)))
    if verbose:
        for p,a,f in cs_all: print('  cand area',int(a),'sup',round(f,3),(p/s).astype(int).tolist())
    good=[r for r in cs_all if r[2]>=0.75]
    if not good: return None
    best=max(good,key=lambda r:r[1]*r[2])
    return order(best[0]/s)
if __name__=="__main__":
    truth={'dark.jpg':[[380,120],[930,170],[1010,860],[290,820]],'light.jpg':[[380,120],[930,170],[1010,860],[290,820]],
    'wood_paper.jpg':[[400,140],[900,190],[960,880],[320,830]],'wood_paper_tilt.jpg':[[300,220],[930,110],[1050,760],[380,900]],'wood_only.jpg':None}
    for f,t in truth.items():
        q=detect(cv2.cvtColor(cv2.imread(f),cv2.COLOR_BGR2GRAY), verbose='-v' in sys.argv)
        err=None if q is None or t is None else float(np.max(np.linalg.norm(q-np.float32(t),axis=1)))
        print(f, 'none' if q is None else q.round().astype(int).tolist(), 'maxerr', err)
