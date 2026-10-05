package io.github.chatglot.fabric.mixin;

import java.util.List;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
    @Accessor("pages")
    List<String> chatglot$getPages();

    @Accessor("currentPage")
    int chatglot$getCurrentPage();

    @Accessor("page")
    MultiLineEditBox chatglot$getPage();

    @Invoker("backgroundLeft")
    int chatglot$backgroundLeft();

    @Invoker("backgroundTop")
    int chatglot$backgroundTop();

    @Invoker("menuControlsTop")
    int chatglot$menuControlsTop();
}
