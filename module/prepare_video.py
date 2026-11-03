"""Offline QHD-preserving conversion; requires FFmpeg/ffprobe, neither needed at runtime."""
from pathlib import Path
import argparse,subprocess,json,tempfile,struct,zlib
from fractions import Fraction
def main():
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    if a.output.exists():raise SystemExit('Output exists; not overwriting')
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=width,height,avg_frame_rate','-of','json',str(a.source)]))['streams'][0]
    width,height=info['width'],info['height'];rate=Fraction(info['avg_frame_rate'])
    if not(16<=width<=3840 and 16<=height<=2160 and 1<=rate<=30):raise SystemExit('Dimensions or FPS outside runtime limits')
    with tempfile.TemporaryDirectory(prefix='ntsd-menu-frames-') as t:
        t=Path(t);subprocess.run(['ffmpeg','-v','error','-i',str(a.source),'-map','0:v:0','-vsync','0','-q:v','2','-pix_fmt','yuvj444p',str(t/'frame-%04d.jpg')],check=True)
        frames=sorted(t.glob('*.jpg'))
        if not 2<=len(frames)<=600:raise SystemExit('Invalid frame count')
        a.output.parent.mkdir(parents=True,exist_ok=True);offset=28+16*len(frames)
        with a.output.open('xb') as out:
            out.write(struct.pack('>8s5i',b'NTSDFR01',width,height,rate.numerator,rate.denominator,len(frames)))
            for f in frames:
                b=f.read_bytes();out.write(struct.pack('>QII',offset,len(b),zlib.crc32(b)));offset+=len(b)
            for f in frames:out.write(f.read_bytes())
    print('Created',a.output,width,height,str(rate),'FPS',len(frames),'frames')
if __name__=='__main__':main()
