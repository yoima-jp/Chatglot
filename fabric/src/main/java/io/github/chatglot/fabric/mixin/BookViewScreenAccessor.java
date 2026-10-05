package io.github.chatglot.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BookViewScreen.class)
public interface BookViewScreenAccessor {
    @Accessor("bookAccess")
    BookViewScreen.BookAccess chatglot$getBookAccess();

    @Accessor("currentPage")
    int chatglot$getCurrentPage();

    @Invoker("backgroundLeft")
    int chatglot$backgroundLeft();

    @Invoker("backgroundTop")
    int chatglot$backgroundTop();

    @Invoker("menuControlsTop")
    int chatglot$menuControlsTop();
}
