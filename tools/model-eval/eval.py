"""Evaluates DocAligner corner models (and the classic detector) on test photos.
Writes overlay sheets + timings into out/. Run by .github/workflows/model-eval.yml."""
import glob, json, os, sys, time
import cv2, numpy as np, onnxruntime as ort

MODELS = sys.argv[1:] or ["lcnet100.onnx", "fastvit_t8.onnx", "fastvit_sa24.onnx"]
imgs = sorted(glob.glob("tools/model-eval/images/*")) + sorted(glob.glob("ext/*/*.*"))
os.makedirs("out", exist_ok=True)

def corners(sess, img, bgr=True):
    h, w = img.shape[:2]
    x = img if bgr else cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    x = cv2.resize(x, (256, 256), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
    x = np.transpose(x, (2, 0, 1))[None]
    name = sess.get_inputs()[0].name
    t = time.time(); outs = sess.run(None, {name: x}); dt = (time.time() - t) * 1000
    names = [o.name for o in sess.get_outputs()]
    hm = outs[names.index("heatmap")] if "heatmap" in names else outs[0]
    pts = []
    for i in range(min(4, hm.shape[1])):
        p = cv2.resize(hm[0, i], (w, h))
        m = (p >= 0.3).astype(np.uint8)
        n, lab, stats, cent = cv2.connectedComponentsWithStats(m)
        if n <= 1: pts.append(None); continue
        k = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
        pts.append((float(cent[k][0]), float(cent[k][1]), float(p[lab == k].max())))
    return pts, dt, names, [o.shape for o in outs]

report = {}
for mf in MODELS:
    if not os.path.exists(mf): continue
    sess = ort.InferenceSession(mf, providers=["CPUExecutionProvider"])
    for bgr in (True, False):
        tiles = []; times = []
        for f in imgs:
            im = cv2.imread(f)
            if im is None: continue
            pts, dt, names, shapes = corners(sess, im, bgr); times.append(dt)
            h, w = im.shape[:2]; s = 300 / max(h, w); v = cv2.resize(im, (int(w * s), int(h * s)))
            good = [p for p in pts if p]
            if len(good) == 4:
                cv2.polylines(v, [np.int32([[p[0] * s, p[1] * s] for p in good])], True, (0, 255, 255), 3)
            for p in good: cv2.circle(v, (int(p[0] * s), int(p[1] * s)), 6, (0, 0, 255), -1)
            t = np.full((330, 310, 3), 255, np.uint8); t[:v.shape[0], :v.shape[1]] = v
            conf = ",".join("%.2f" % p[2] for p in good)
            cv2.putText(t, "%s n=%d %s" % (os.path.basename(f)[:14], len(good), conf), (2, 322), 0, 0.38, (0, 0, 200), 1)
            tiles.append(t)
        while len(tiles) % 6: tiles.append(np.full((330, 310, 3), 255, np.uint8))
        sheet = np.vstack([np.hstack(tiles[i:i + 6]) for i in range(0, len(tiles), 6)])
        tag = os.path.basename(mf).replace(".onnx", "") + ("_bgr" if bgr else "_rgb")
        cv2.imwrite("out/%s.jpg" % tag, sheet, [cv2.IMWRITE_JPEG_QUALITY, 80])
        report[tag] = {"median_ms": float(np.median(times)), "outputs": names, "shapes": [list(map(int, s)) for s in shapes],
                       "size_mb": os.path.getsize(mf) / 1e6}
json.dump(report, open("out/report.json", "w"), indent=1)
print(json.dumps(report, indent=1))
