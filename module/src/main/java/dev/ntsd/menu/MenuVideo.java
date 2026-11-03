// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.lwjgl.opengl.GL32C.*;

/** Playback and GPU allocation exist only around TitleScreen. No widget manipulation. */
public final class MenuVideo {
    static final Logger LOG=LoggerFactory.getLogger("NTSD-MENU");
    static FrameStream stream;static boolean disabled,failed,prepared;
    static final int[] textures=new int[2];static final long[] numbers={-1,-1};
    static long start,uploads,draws,held,loops,session;static double lastPosition;
    static String failure="";static long lastDecoded;static double lastDecodeMean;
    static long preparationNanos;
    public static void prepare(){
        if(prepared)return;prepared=true;failed=false;failure="";disabled=false;
        try{
            Path dir=FMLPaths.CONFIGDIR.get().resolve("ntsd-menu");Properties cfg=new Properties();
            try(var f=Files.newBufferedReader(dir.resolve("menu.properties"),StandardCharsets.UTF_8)){cfg.load(f);}
            disabled=!Boolean.parseBoolean(cfg.getProperty("enabled","true"))||Boolean.getBoolean("ntsd.menu.disabled");if(disabled)return;
            Path seq=dir.resolve(cfg.getProperty("sequence","orbital.ntsd")).normalize();if(!seq.startsWith(dir.normalize()))throw new IllegalArgumentException("Invalid sequence path");
            long before=System.nanoTime();stream=new FrameStream(seq,false);preparationNanos=System.nanoTime()-before;
            start=0;Arrays.fill(numbers,-1);session++;lastPosition=0;
            LOG.info("Prepared title background {}x{}, {} FPS, {} frames; native title widgets retained",stream.width,stream.height,stream.fps,stream.count);
        }catch(Throwable e){fail(e);}
    }
    public static boolean draw(GuiGraphics graphics,int width,int height){
        if(disabled||failed||!prepared||stream==null)return false;
        try{
            if(stream.failure!=null)throw new IllegalStateException("Menu decoder failed",stream.failure);
            long target=start==0?0:(long)((System.nanoTime()-start)*stream.fps/1e9);
            var pending=stream.peek();if(pending!=null&&pending.number()<=target+1){var f=stream.take();if(f!=null)try{
                if(f.number()>=target){int slot=numbers[0]<=numbers[1]?0:1;upload(slot,f);if(start==0){start=System.nanoTime();target=0;}}
            }finally{f.close();}}
            int a=find(target),b=find(target+1);
            if(a<0){a=numbers[0]<0&&numbers[1]<0?-1:numbers[0]>numbers[1]?0:1;if(a>=0)held++;stream.request(target);}
            else stream.request(b<0?target+1:target+2);
            if(a<0)return false;
            graphics.flush();RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,GlStateManager.SourceFactor.ONE,GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.setShaderColor(1,1,1,1);float fraction=(float)(((System.nanoTime()-start)*stream.fps/1e9)-target);
            quad(textures[a],width,height,1);if(b>=0)quad(textures[b],width,height,Math.max(0,Math.min(1,fraction)));
            long currentLoop=target/stream.count;if(currentLoop>lastPosition){loops+=currentLoop-(long)lastPosition;lastPosition=currentLoop;}
            draws++;return true;
        }catch(Throwable e){fail(e);return false;}
        finally{RenderSystem.defaultBlendFunc();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.setShaderColor(1,1,1,1);}
    }
    private static int find(long n){for(int i=0;i<2;i++)if(numbers[i]==n)return i;return -1;}
    private static void upload(int slot,FrameStream.Frame f){
        ReturnVerification.beforeUpload();
        // NativeImage/font uploads leave global unpack state behind. Video frames
        // are tightly packed RGBA, not subrectangles of the previous GUI image.
        int row=glGetInteger(GL_UNPACK_ROW_LENGTH),skipRows=glGetInteger(GL_UNPACK_SKIP_ROWS),skipPixels=glGetInteger(GL_UNPACK_SKIP_PIXELS),alignment=glGetInteger(GL_UNPACK_ALIGNMENT),pbo=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try{
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
            glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            RenderSystem.activeTexture(GL_TEXTURE0);
            if(textures[slot]==0){textures[slot]=TextureUtil.generateTextureId();RenderSystem.bindTexture(textures[slot]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,stream.width,stream.height,0,GL_RGBA,GL_UNSIGNED_BYTE,f.pixels());}
            else{RenderSystem.bindTexture(textures[slot]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,stream.width,stream.height,GL_RGBA,GL_UNSIGNED_BYTE,f.pixels());}
        }finally{
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,pbo);
            glPixelStorei(GL_UNPACK_ROW_LENGTH,row);glPixelStorei(GL_UNPACK_SKIP_ROWS,skipRows);glPixelStorei(GL_UNPACK_SKIP_PIXELS,skipPixels);glPixelStorei(GL_UNPACK_ALIGNMENT,alignment);
        }
        ReturnVerification.uploaded(textures[slot],f,stream.width,stream.height);
        numbers[slot]=f.number();uploads++;
    }
    static float[] crop(int w,int h,int iw,int ih){double r=(double)w/h,s=(double)iw/ih;float u=0,v=0;if(r>s)v=(float)((1-s/r)/2);else u=(float)((1-r/s)/2);return new float[]{u,1-u,v,1-v};}
    private static void quad(int texture,int w,int h,float alpha){
        float[] uv=crop(w,h,stream.width,stream.height);RenderSystem.setShader(GameRenderer::getPositionTexColorShader);RenderSystem.setShaderTexture(0,texture);
        var bb=Tesselator.getInstance().getBuilder();bb.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(0,h,0).uv(uv[0],uv[3]).color(1,1,1,alpha).endVertex();bb.vertex(w,h,0).uv(uv[1],uv[3]).color(1,1,1,alpha).endVertex();bb.vertex(w,0,0).uv(uv[1],uv[2]).color(1,1,1,alpha).endVertex();bb.vertex(0,0,0).uv(uv[0],uv[2]).color(1,1,1,alpha).endVertex();BufferUploader.drawWithShader(bb.end());
    }
    static void fail(Throwable e){failed=true;failure=e.toString();LOG.warn("Title video unavailable; using unchanged native panorama",e);release();report();}
    static void release(){
        if(stream!=null){lastDecoded=stream.decoded;lastDecodeMean=stream.decoded==0?0:stream.decodeNanos/1e6/stream.decoded;stream.close();stream=null;}
        for(int i=0;i<2;i++)if(textures[i]!=0){TextureUtil.releaseTextureId(textures[i]);textures[i]=0;}
        Arrays.fill(numbers,-1);start=0;
    }
    public static void stop(){if(!prepared)return;release();prepared=false;report();}
    static void report(){try{
        Properties p=new Properties();p.setProperty("failure",failure);p.setProperty("draws",Long.toString(draws));p.setProperty("uploads",Long.toString(uploads));p.setProperty("heldFrames",Long.toString(held));p.setProperty("loops",Long.toString(loops));p.setProperty("session",Long.toString(session));p.setProperty("prepared",Boolean.toString(prepared));p.setProperty("gpuTextures",Integer.toString((textures[0]!=0?1:0)+(textures[1]!=0?1:0)));p.setProperty("lastSessionDecoded",Long.toString(lastDecoded));p.setProperty("lastSessionMeanDecodeMillis",Double.toString(lastDecodeMean));
        Path out=FMLPaths.GAMEDIR.get().resolve("logs/ntsd-menu.properties");Files.createDirectories(out.getParent());try(var f=Files.newBufferedWriter(out,StandardCharsets.UTF_8)){p.store(f,"NTSD background only; native buttons unchanged");}
    }catch(Exception e){LOG.warn("Could not write title diagnostics",e);}}
}
