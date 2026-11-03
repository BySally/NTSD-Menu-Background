// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.TitleScreen;

@Mod("ntsdmenu")
public final class NtsdMenu {
    @Mod.EventBusSubscriber(modid="ntsdmenu",bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(MenuVideo::prepare);}
    }
    @Mod.EventBusSubscriber(modid="ntsdmenu",value=Dist.CLIENT)
    public static final class Lifecycle {
        @SubscribeEvent public static void opening(ScreenEvent.Opening e){
            if(e.getNewScreen() instanceof TitleScreen)MenuVideo.prepare();else MenuVideo.stop();
        }
    }
}
