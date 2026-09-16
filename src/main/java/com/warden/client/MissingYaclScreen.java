package com.warden.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Shown instead of the YACL screen when YACL isn't loaded, so the ModMenu button never looks dead. */
public class MissingYaclScreen extends Screen {

    private final Screen parent;

    public MissingYaclScreen(Screen parent) {
        super(Text.literal("Warden"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(width / 2 - 100, height / 2 + 30, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 30, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("The config screen needs YACL (Yet Another Config Lib) installed.").formatted(Formatting.GRAY),
                width / 2, height / 2 - 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Get it from modrinth.com/mod/yacl and restart the game.").formatted(Formatting.GRAY),
                width / 2, height / 2 + 4, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
