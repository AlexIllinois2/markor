import zlib, struct, sys

def load(path):
    d = open(path,'rb').read(); pos=8; idat=b''
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]; typ=d[pos+4:pos+8]
        data=d[pos+8:pos+8+ln]; pos+=12+ln
        if typ==b'IHDR': w,h,bd,ct,_,_,il = struct.unpack('>IIBBBBB', data)
        elif typ==b'IDAT': idat+=data
        elif typ==b'IEND': break
    raw=zlib.decompress(idat); ch={0:1,2:3,4:2,6:4}[ct]; stride=w*ch
    out=bytearray(h*stride); prev=bytearray(stride); p=0
    for y in range(h):
        f=raw[p]; p+=1; line=bytearray(raw[p:p+stride]); p+=stride
        if f==1:
            for i in range(ch,stride): line[i]=(line[i]+line[i-ch])&255
        elif f==2:
            for i in range(stride): line[i]=(line[i]+prev[i])&255
        elif f==3:
            for i in range(stride):
                a=line[i-ch] if i>=ch else 0
                line[i]=(line[i]+((a+prev[i])>>1))&255
        elif f==4:
            for i in range(stride):
                a=line[i-ch] if i>=ch else 0; b=prev[i]; c=prev[i-ch] if i>=ch else 0
                pp=a+b-c; pa=abs(pp-a); pb=abs(pp-b); pc=abs(pp-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[i]=(line[i]+pr)&255
        out[y*stride:(y+1)*stride]=line; prev=line
    return w,h,ch,out

def save(path, w, h, ch, buf):
    raw=bytearray()
    stride=w*ch
    for y in range(h):
        raw.append(0); raw+=buf[y*stride:(y+1)*stride]
    ct={1:0,3:2,4:6}[ch]
    def chunk(t,data):
        c=struct.pack('>I',len(data))+t+data
        return c+struct.pack('>I', zlib.crc32(t+data)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,ct,0,0,0))
    png+=chunk(b'IDAT',zlib.compress(bytes(raw),6))+chunk(b'IEND',b'')
    open(path,'wb').write(png)

src,dst,x0,y0,x1,y1 = sys.argv[1],sys.argv[2],*map(int,sys.argv[3:7])
w,h,ch,buf=load(src)
x0=max(0,x0); y0=max(0,y0); x1=min(w,x1); y1=min(h,y1)
cw,chh=x1-x0,y1-y0
out=bytearray(cw*chh*ch)
for y in range(chh):
    s=((y0+y)*w+x0)*ch
    out[y*cw*ch:(y+1)*cw*ch]=buf[s:s+cw*ch]
save(dst,cw,chh,ch,out)
print("cropped", dst, cw, chh)
