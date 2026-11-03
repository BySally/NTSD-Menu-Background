// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/** External artwork stays separately licensed; GLFW copies these RGBA buffers. */
public final class NtsdWindowIcon {
    public static boolean apply(long window) {
        var pixels = new ArrayList<ByteBuffer>();
        int[] sizes = {16,24,32,48,64,128,256};
        try (var frames = GLFWImage.malloc(sizes.length)) {
            for (int i=0;i<sizes.length;i++) {
                int size=sizes[i];
                byte[] bytes=Files.readAllBytes(FMLPaths.CONFIGDIR.get().resolve("ntsd-branding/icon-"+size+".png"));
                if(bytes.length>1024*1024)throw new IllegalArgumentException("Oversized icon frame");
                ByteBuffer encoded=MemoryUtil.memAlloc(bytes.length);
                try {
                    encoded.put(bytes).flip();
                    int[] width=new int[1],height=new int[1],channels=new int[1];
                    ByteBuffer rgba=STBImage.stbi_load_from_memory(encoded,width,height,channels,4);
                    if(rgba==null)throw new IllegalArgumentException("Invalid icon PNG");
                    pixels.add(rgba);
                    if(width[0]!=size||height[0]!=size)throw new IllegalArgumentException("Incorrect icon dimensions");
                    frames.position(i).width(size).height(size).pixels(rgba);
                } finally {MemoryUtil.memFree(encoded);}
            }
            frames.position(0);GLFW.glfwSetWindowIcon(window,frames);
            LoggerFactory.getLogger("NTSD-ICON").info("NTSD icon applied to window {}: 16..256 px",window);
            return true;
        } catch (Exception error) {
            LoggerFactory.getLogger("NTSD-ICON").warn("NTSD icon unavailable; retaining platform/default icon: {}",error.toString());
            return false;
        } finally {pixels.forEach(STBImage::stbi_image_free);}
    }
}
