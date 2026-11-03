// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import static org.lwjgl.opengl.GL32C.*;

/** Opt-in regression: checks actual uploaded pixels after native screen returns. */
@Mod.EventBusSubscriber(modid="ntsdmenu",value=Dist.CLIENT)
public final class ReturnVerification {
    static final boolean RUN=Boolean.getBoolean("ntsd.menu.returnVerify");
    static final Path DIR=FMLPaths.GAMEDIR.get().resolve("ntsd-return-verification");
    static final JsonObject report=new JsonObject();
    static final JsonArray uploads=new JsonArray(),returns=new JsonArray();
    static final String[] BUTTONS={"menu.options","menu.multiplayer","fml.menu.mods","narrator.button.language","menu.options","menu.options"};
    static int phase,index,checkedUploads;static long since,checkedSession=-1,drawsBefore,uploadsBefore;
    static TitleScreen title;static JsonArray original;static boolean done,busy,mismatch;
    static int[] unpack;
    public static void beforeUpload(){
        if(!RUN)return;
        unpack=new int[]{glGetInteger(GL_UNPACK_ROW_LENGTH),glGetInteger(GL_UNPACK_SKIP_ROWS),glGetInteger(GL_UNPACK_SKIP_PIXELS),glGetInteger(GL_UNPACK_ALIGNMENT),glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)};
    }
    public static void uploaded(int texture,FrameStream.Frame frame,int width,int height){
        if(!RUN)return;
        if(checkedSession!=MenuVideo.session){checkedSession=MenuVideo.session;checkedUploads=0;}
        if(checkedUploads++>=2)return;
        int alignment=glGetInteger(GL_PACK_ALIGNMENT),row=glGetInteger(GL_PACK_ROW_LENGTH),skipRows=glGetInteger(GL_PACK_SKIP_ROWS),skipPixels=glGetInteger(GL_PACK_SKIP_PIXELS),pbo=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        ByteBuffer actual=MemoryUtil.memAlloc(width*height*4);
        try{
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,actual);
            CRC32 expectedCRC=new CRC32(),actualCRC=new CRC32();expectedCRC.update(frame.pixels().duplicate());actualCRC.update(actual);
            boolean matches=expectedCRC.getValue()==actualCRC.getValue();mismatch|=!matches;
            int[] restored={glGetInteger(GL_UNPACK_ROW_LENGTH),glGetInteger(GL_UNPACK_SKIP_ROWS),glGetInteger(GL_UNPACK_SKIP_PIXELS),glGetInteger(GL_UNPACK_ALIGNMENT),glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)};
            if(!Arrays.equals(unpack,restored))throw new AssertionError("Foreign pixel unpack state not restored");
            var r=new JsonObject();r.addProperty("session",MenuVideo.session);r.addProperty("frame",frame.number());r.addProperty("texture",texture);r.addProperty("expected_crc32",expectedCRC.getValue());r.addProperty("actual_crc32",actualCRC.getValue());r.addProperty("pixels_match",matches);r.addProperty("foreign_unpack_state_restored",true);r.add("unpack_before_upload",new Gson().toJsonTree(unpack));uploads.add(r);
        }finally{
            MemoryUtil.memFree(actual);glBindBuffer(GL_PIXEL_PACK_BUFFER,pbo);glPixelStorei(GL_PACK_ALIGNMENT,alignment);glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_ROWS,skipRows);glPixelStorei(GL_PACK_SKIP_PIXELS,skipPixels);
        }
    }
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent e){
        if(!RUN||done||busy||e.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        busy=true;
        try{
            long now=System.nanoTime();
            if(phase==0){if(!(mc.screen instanceof TitleScreen t))return;title=t;original=MenuVerification.buttons(title);since=now;phase=1;return;}
            if(phase==1){
                if(now-since<10_000_000_000L)return;
                verifyPixels();if(MenuVideo.loops<1)throw new AssertionError("Video did not complete one loop");report.addProperty("loop_observed",true);MenuVerification.capture(title,2560,1440,"return-before");openNext(mc,now);return;
            }
            if(phase==2){
                if(now-since<1_000_000_000L)return;
                if(mc.screen instanceof TitleScreen)throw new AssertionError("Native screen failed to open");
                if(MenuVideo.stream!=null||MenuVideo.textures[0]!=0||MenuVideo.textures[1]!=0)throw new AssertionError("Playback resources remained outside title");
                if(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("NTSD menu decoder")))throw new AssertionError("Menu decoder remained alive outside title");
                String destination=mc.screen.getClass().getName();
                mc.screen.onClose(); // Use the actual parent-screen lifecycle, not setScreen(title).
                if(!(mc.screen instanceof TitleScreen))throw new AssertionError("Native onClose did not return to TitleScreen");
                var r=new JsonObject();r.addProperty("button",BUTTONS[index-1]);r.addProperty("destination",destination);r.addProperty("decoder_and_textures_released_while_away",true);returns.add(r);
                drawsBefore=MenuVideo.draws;uploadsBefore=MenuVideo.uploads;since=now;phase=3;return;
            }
            if(phase==3){
                if(now-since<1_500_000_000L)return;
                MenuVerification.capture(title,2560,1440,"return-"+index);
                verifyPixels();
                if(MenuVideo.draws<=drawsBefore||MenuVideo.uploads<=uploadsBefore||MenuVideo.stream==null||MenuVideo.failed)throw new AssertionError("Playback did not restart after native return");
                if(!original.equals(MenuVerification.buttons(title)))throw new AssertionError("Native buttons changed");
                if(index<BUTTONS.length){openNext(mc,now);return;}
                MenuVerification.capture(title,1280,720,"return-final-hd");MenuVerification.capture(title,5120,1440,"return-final-ultrawide");
                report.addProperty("animation_restarted_after_all_returns",true);report.addProperty("native_buttons_unchanged",true);report.addProperty("all_texture_uploads_match_decoded_pixels",true);report.addProperty("passed",true);finish(mc);return;
            }
        }catch(Throwable error){report.addProperty("passed",false);report.addProperty("error",error.toString());MenuVideo.LOG.error("Title return regression failed",error);finish(mc);}
        finally{busy=false;}
    }
    static void verifyPixels(){if(mismatch)throw new AssertionError("GPU texture differs from decoded video frame");if(uploads.isEmpty())throw new AssertionError("No video texture checked");}
    static void openNext(Minecraft mc,long now){
        String key=BUTTONS[index++];
        var button=title.children().stream().filter(c->c instanceof AbstractButton).map(c->(AbstractButton)c).filter(b->MenuVerification.translation(b).equals(key)).findFirst().orElseThrow(()->new AssertionError("Missing button "+key));
        button.onPress();since=now;phase=2;
    }
    static void finish(Minecraft mc){
        done=true;report.add("upload_checks",uploads);report.add("native_returns",returns);report.addProperty("test_only",true);report.addProperty("no_world_loaded",mc.level==null);
        try{Files.createDirectories(DIR);Files.writeString(DIR.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}catch(Exception error){MenuVideo.LOG.error("Cannot record regression",error);}
        MenuVideo.stop();mc.stop();
    }
}
