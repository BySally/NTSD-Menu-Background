// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;
import com.google.gson.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.lwjgl.opengl.GL32C.*;

/** Opt-in disposable-profile verification only. Default false: zero UI/test actions. */
@Mod.EventBusSubscriber(modid="ntsdmenu",value=Dist.CLIENT)
public final class MenuVerification {
    static final boolean RUN=Boolean.getBoolean("ntsd.menu.verify");
    static final JsonObject report=new JsonObject();static final JsonArray actions=new JsonArray();
    static int phase;static long since,menuFrames;static TitleScreen title;static boolean busy,done;
    static final String[] BUTTONS={"menu.options","menu.singleplayer","menu.multiplayer","fml.menu.mods"};
    static int buttonIndex;static long deadline;static JsonArray original;
    static final Path DIR=FMLPaths.GAMEDIR.get().resolve("ntsd-menu-verification");
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent e){
        if(!RUN||done||busy||e.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        busy=true;
        try{
            long now=System.nanoTime();
            if(phase==0){
                if(!(mc.screen instanceof TitleScreen t))return;title=t;since=now;phase=1;return;
            }
            if(phase==1){
                menuFrames++;boolean baseline=Boolean.getBoolean("ntsd.menu.disabled"),fallback=Boolean.getBoolean("ntsd.menu.expectFallback");
                if(now-since<(baseline?4:11)*1_000_000_000L)return;
                if(!baseline&&!fallback&&MenuVideo.draws==0)throw new AssertionError("Video hook did not draw");
                if(!baseline&&!fallback&&MenuVideo.loops<1)throw new AssertionError("Full loop not completed");
                if(fallback&&!MenuVideo.failed)throw new AssertionError("Corrupt-frame fallback did not occur");
                original=buttons(title);report.add("native_buttons",original);report.addProperty("main_screen_class",title.getClass().getName());report.addProperty("loop_observed",MenuVideo.loops>0);report.addProperty("draws",MenuVideo.draws);report.addProperty("uploads",MenuVideo.uploads);report.addProperty("held_frames",MenuVideo.held);report.addProperty("average_render_fps",menuFrames/((now-since)/1e9));
                if(MenuVideo.stream!=null){report.addProperty("decoded_frames",MenuVideo.stream.decoded);report.addProperty("mean_decode_ms",MenuVideo.stream.decodeNanos/1e6/Math.max(1,MenuVideo.stream.decoded));report.addProperty("max_decode_ms",MenuVideo.stream.maxDecodeNanos/1e6);}
                report.addProperty("project_credit",ProjectCredit.TEXT);
                capture(title,1280,720,"main-hd");capture(title,2560,1440,"main-qhd");capture(title,5120,1440,"main-ultrawide");
                original=buttons(title);report.add("native_buttons_after_resize",original);pressNext(mc,now);return;
            }
            if(phase==2){
                if(now<deadline)return;
                if(mc.screen instanceof TitleScreen)throw new AssertionError("Native button did not leave title");
                if(MenuVideo.stream!=null||MenuVideo.textures[0]!=0||MenuVideo.textures[1]!=0)throw new AssertionError("Playback resources remained outside title");
                if(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("NTSD menu decoder")))throw new AssertionError("Menu decoder remained alive outside title");
                var a=new JsonObject();a.addProperty("button",BUTTONS[buttonIndex-1]);a.addProperty("destination",mc.screen==null?"null":mc.screen.getClass().getName());a.addProperty("decoder_and_textures_released",true);actions.add(a);
                if(buttonIndex==1)capture(mc.screen,1280,720,"options-native");
                mc.setScreen(title);deadline=now+800_000_000L;phase=3;return;
            }
            if(phase==3){
                if(now<deadline)return;if(!(mc.screen instanceof TitleScreen))throw new AssertionError("Title return failed");
                if(!original.equals(buttons(title)))throw new AssertionError("Native widgets changed after leaving/returning");
                if(buttonIndex<BUTTONS.length){pressNext(mc,now);return;}
                report.add("native_button_actions",actions);report.addProperty("native_buttons_preserved_after_return",true);report.addProperty("fallback",MenuVideo.failure);
                report.addProperty("no_world_loaded",mc.level==null);report.addProperty("passed",true);finish(mc);return;
            }
        }catch(Throwable error){report.addProperty("passed",false);report.addProperty("error",error.toString());MenuVideo.LOG.error("NTSD menu verification failed",error);finish(mc);}
        finally{busy=false;}
    }
    static void pressNext(Minecraft mc,long now){
        String key=BUTTONS[buttonIndex++];var list=title.children().stream().filter(c->c instanceof AbstractButton).map(c->(AbstractButton)c).toList();
        var wanted=list.stream().filter(b->translation(b).equals(key)).findFirst();
        if(wanted.isEmpty()&&key.equals("fml.menu.mods"))wanted=list.stream().filter(b->translation(b).contains("mods")).findFirst();
        var button=wanted.orElseThrow(()->new AssertionError("Native button missing: "+key));if(!button.active)throw new AssertionError("Native button disabled: "+key);button.onPress();deadline=now+800_000_000L;phase=2;
    }
    static String translation(AbstractWidget b){var c=b.getMessage().getContents();return c instanceof net.minecraft.network.chat.contents.TranslatableContents t?t.getKey():b.getMessage().getString();}
    static JsonArray buttons(Screen s){var a=new JsonArray();for(var c:s.children())if(c instanceof AbstractWidget b){var o=new JsonObject();o.addProperty("class",b.getClass().getName());o.addProperty("key",translation(b));o.addProperty("label",b.getMessage().getString());o.addProperty("x",b.getX());o.addProperty("y",b.getY());o.addProperty("w",b.getWidth());o.addProperty("h",b.getHeight());o.addProperty("active",b.active);o.addProperty("visible",b.visible);a.add(o);}return a;}
    static void capture(Screen screen,int w,int h,String name)throws Exception{
        var mc=Minecraft.getInstance();int sw=screen.width,sh=screen.height;var target=new TextureTarget(w,h,true,Minecraft.ON_OSX);Matrix4f old=new Matrix4f(RenderSystem.getProjectionMatrix());var sorting=RenderSystem.getVertexSorting();var mv=RenderSystem.getModelViewStack();mv.pushPose();
        try{
            target.setClearColor(0,0,0,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,w/2f,h/2f,0,1000,21000),VertexSorting.ORTHOGRAPHIC_Z);mv.setIdentity();mv.translate(0,0,-11000);RenderSystem.applyModelViewMatrix();
            screen.resize(mc,w/2,h/2);var graphics=new GuiGraphics(mc,mc.renderBuffers().bufferSource());screen.render(graphics,-999,-999,0);graphics.flush();
            if(screen instanceof TitleScreen t){
                int[] r=ProjectCredit.bounds(t);
                if(r==null||r[0]<0||r[1]<0||r[0]+r[2]>t.width||r[1]+r[3]>t.height-12)throw new AssertionError("Credit outside safe viewport");
                var entry=new JsonObject();entry.addProperty("viewport",w+"x"+h);entry.addProperty("x",r[0]);entry.addProperty("y",r[1]);entry.addProperty("width",r[2]);entry.addProperty("height",r[3]);
                if(!report.has("credit_layouts"))report.add("credit_layouts",new JsonArray());report.getAsJsonArray("credit_layouts").add(entry);
            }
            Files.createDirectories(DIR);try(var image=Screenshot.takeScreenshot(target)){image.writeToFile(DIR.resolve(name+".png"));}
            int err=glGetError();if(err!=GL_NO_ERROR)throw new AssertionError("OpenGL error in viewport capture: "+err);
        }finally{screen.resize(mc,sw,sh);mv.popPose();RenderSystem.applyModelViewMatrix();RenderSystem.setProjectionMatrix(old,sorting);mc.getMainRenderTarget().bindWrite(true);target.destroyBuffers();}
    }
    static void finish(Minecraft mc){
        done=true;MenuVideo.stop();report.addProperty("test_only",true);
        try{Files.createDirectories(DIR);Files.writeString(DIR.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardCharsets.UTF_8);}catch(Exception e){MenuVideo.LOG.error("Cannot write test result",e);}
        mc.stop();
    }
}
