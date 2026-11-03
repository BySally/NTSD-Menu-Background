// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.CRC32;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.*;

/** One decoder, one pending frame. JPEGs are indexed on disk, never all expanded. */
final class FrameStream implements AutoCloseable {
    record Frame(long number,ByteBuffer pixels) implements AutoCloseable {
        public void close(){STBImage.stbi_image_free(pixels);}
    }
    final int width,height,count,loopFrames;final double fps;
    private final long[] offsets;private final int[] lengths,checksums;
    private final FileChannel channel;
    private final AtomicLong requested=new AtomicLong(0);
    private final Semaphore wake=new Semaphore(1);
    private final AtomicReference<Frame> pending=new AtomicReference<>();
    private volatile boolean closed;
    volatile Throwable failure;
    volatile long decoded,decodeNanos,maxDecodeNanos;
    volatile long liveBytes,peakBytes;
    FrameStream(Path path,boolean pingPong)throws IOException {
        channel=FileChannel.open(path,StandardOpenOption.READ);
        try {
            ByteBuffer h=ByteBuffer.allocate(28);read(h,0);h.flip();
            if(h.getLong()!=0x4e54534446523031L)throw new IOException("Invalid animation signature");
            width=h.getInt();height=h.getInt();int numerator=h.getInt(),denominator=h.getInt();count=h.getInt();
            if(width<16||height<16||width>3840||height>2160||count<2||count>600||numerator<1||denominator<1)throw new IOException("Invalid animation dimensions/timing");
            fps=(double)numerator/denominator;if(fps>30||fps<1)throw new IOException("Unsupported animation rate");
            loopFrames=pingPong?count*2-2:count;
            offsets=new long[count];lengths=new int[count];checksums=new int[count];
            ByteBuffer index=ByteBuffer.allocate(count*16);read(index,28);index.flip();long end=28L+count*16;
            for(int i=0;i<count;i++){
                offsets[i]=index.getLong();lengths[i]=index.getInt();checksums[i]=index.getInt();
                if(offsets[i]!=end||lengths[i]<4||lengths[i]>8_000_000||offsets[i]+lengths[i]>channel.size())throw new IOException("Invalid animation index "+i);
                end+=lengths[i];
            }
            if(end!=channel.size())throw new IOException("Unexpected animation trailing data");
        }catch(Throwable error){channel.close();throw error;}
        Thread worker=new Thread(this::decode,"NTSD menu decoder");worker.setDaemon(true);worker.setPriority(Thread.MIN_PRIORITY);worker.start();
    }
    private void read(ByteBuffer out,long offset)throws IOException {
        while(out.hasRemaining()){int n=channel.read(out,offset);if(n<0)throw new EOFException("Truncated animation");if(n==0)throw new IOException("Animation read stalled");offset+=n;}
    }
    void request(long number){requested.set(number);if(wake.availablePermits()==0)wake.release();}
    Frame peek(){return pending.get();}
    Frame take(){return pending.getAndSet(null);}
    private void decode(){
        try{
            while(!closed){
                if(!wake.tryAcquire(1,TimeUnit.SECONDS))continue;
                if(closed)break;if(pending.get()!=null)continue;
                long number=requested.get();int position=(int)(number%loopFrames);int index=position<count?position:loopFrames-position;long start=System.nanoTime();
                ByteBuffer encoded=MemoryUtil.memAlloc(lengths[index]);
                try{
                    read(encoded,offsets[index]);encoded.flip();CRC32 crc=new CRC32();crc.update(encoded.duplicate());
                    if((int)crc.getValue()!=checksums[index])throw new IOException("Animation CRC mismatch at frame "+index);
                    try(MemoryStack stack=MemoryStack.stackPush()){
                        IntBuffer w=stack.mallocInt(1),h=stack.mallocInt(1),c=stack.mallocInt(1);
                        ByteBuffer rgba=STBImage.stbi_load_from_memory(encoded,w,h,c,4);
                        if(rgba==null)throw new IOException("Cannot decode animation: "+STBImage.stbi_failure_reason());
                        if(w.get(0)!=width||h.get(0)!=height){STBImage.stbi_image_free(rgba);throw new IOException("Frame dimensions differ");}
                        var frame=new Frame(number,rgba);
                        if(closed||!pending.compareAndSet(null,frame))frame.close();
                        else {liveBytes=(long)width*height*4;peakBytes=Math.max(peakBytes,liveBytes);}
                    }
                }finally{MemoryUtil.memFree(encoded);}
                long elapsed=System.nanoTime()-start;decoded++;decodeNanos+=elapsed;maxDecodeNanos=Math.max(maxDecodeNanos,elapsed);
            }
        }catch(Throwable error){if(!closed)failure=error;}
        finally{
            try{channel.close();}catch(IOException ignored){}
            if(closed){var f=pending.getAndSet(null);if(f!=null)f.close();liveBytes=0;}
        }
    }
    @Override public void close(){closed=true;wake.release();var f=pending.getAndSet(null);if(f!=null)f.close();liveBytes=0;}
}
