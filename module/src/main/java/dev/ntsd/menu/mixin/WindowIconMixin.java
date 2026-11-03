// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.IconSet;
import dev.ntsd.menu.NtsdWindowIcon;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent vanilla's startup/reload icon from replacing NTSD between client ticks. */
@Mixin(Window.class)
public abstract class WindowIconMixin {
    @Inject(method="m_280655_",at=@At("HEAD"),cancellable=true,remap=false)
    private void ntsd$icon(PackResources resources,IconSet iconSet,CallbackInfo ci) {
        if(NtsdWindowIcon.apply(((Window)(Object)this).getWindow()))ci.cancel();
    }
}
