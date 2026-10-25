package io.github.chatglot.fabric.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatScreen.class)
public interface ChatScreenAccessor {
    @Accessor("input")
    EditBox chatglot$getInput();

    @Accessor("commandSuggestions")
    CommandSuggestions chatglot$getCommandSuggestions();

    @Invoker("normalizeChatMessage")
    String chatglot$normalizeChatMessage(String message);
}
