// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu.mixin;
import dev.ntsd.menu.MenuVideo;
import dev.ntsd.menu.ProjectCredit;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Two background redirects and a small attribution overlay. Native widgets stay intact.
 * Pinned 1.20.1 SRG targets avoid dependence on a development-only refmap. */
@Mixin(TitleScreen.class)
public abstract class TitleBackgroundMixin {
    @Inject(method="m_88315_",at=@At("TAIL"),remap=false)
    private void ntsd$credit(GuiGraphics graphics,int mx,int my,float dt,CallbackInfo ci){
        ProjectCredit.draw(graphics,(TitleScreen)(Object)this);
    }
    @Unique private boolean ntsd$video;
    @Redirect(method="m_88315_",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/PanoramaRenderer;m_110003_(FF)V",remap=false),remap=false)
    private void ntsd$panorama(PanoramaRenderer original,float partial,float alpha,GuiGraphics graphics,int mx,int my,float dt){
        TitleScreen self=(TitleScreen)(Object)this;
        ntsd$video=MenuVideo.draw(graphics,self.width,self.height);
        if(!ntsd$video)original.render(partial,alpha);
    }
    @Redirect(method="m_88315_",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;m_280411_(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V",remap=false),remap=false)
    private void ntsd$overlay(GuiGraphics g,ResourceLocation tex,int x,int y,int w,int h,float u,float v,int uw,int vh,int tw,int th){
        if(!ntsd$video)g.blit(tex,x,y,w,h,u,v,uw,vh,tw,th);
    }
}
