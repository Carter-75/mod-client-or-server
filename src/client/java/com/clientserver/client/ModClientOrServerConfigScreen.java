package com.clientserver.client;

import com.clientserver.ModClientOrServer;
import com.clientserver.util.ModSortUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Mod Menu config screen that sorts installed mods into environment-specific zip bundles.
 */
public class ModClientOrServerConfigScreen extends Screen {

    private final Screen parent;
    private ButtonWidget sortButton;
    private boolean inProgress;
    private Text statusMessage = Text.empty();
    private int statusColor = 0xFFFFFFFF;
    private List<Text> detailLines = Collections.emptyList();

    public ModClientOrServerConfigScreen(Screen parent) {
        super(Text.literal("Mod Client or Server"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 220;
        int buttonHeight = 20;
        int top = this.height / 2 - 10;

        sortButton = ButtonWidget.builder(Text.literal(inProgress ? "Sorting Mods..." : "Sort Mods Into Zip Sets"), button -> startSortTask())
            .dimensions(centerX - buttonWidth / 2, top, buttonWidth, buttonHeight)
            .build();
        sortButton.active = !inProgress;
        addDrawableChild(sortButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> close())
            .dimensions(centerX - 60, top + 30, 120, buttonHeight)
            .build());
    }

    private void startSortTask() {
        if (inProgress) {
            return;
        }
        inProgress = true;
        updateStatus(Text.literal("Sorting mods..."), 0xFFE0C15A, Collections.emptyList());
        if (sortButton != null) {
            sortButton.active = false;
            sortButton.setMessage(Text.literal("Sorting Mods..."));
        }

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return ModSortUtil.sortModsIntoZips();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            })
            .whenComplete((result, throwable) -> {
                if (this.client == null) {
                    inProgress = false;
                    return;
                }
                this.client.execute(() -> {
                    inProgress = false;
                    if (sortButton != null) {
                        sortButton.active = true;
                        sortButton.setMessage(Text.literal("Sort Mods Into Zip Sets"));
                    }

                    if (throwable != null) {
                        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                            ? throwable.getCause()
                            : throwable;
                        String message = cause.getMessage();
                        if (message == null || message.isBlank()) {
                            message = cause.getClass().getSimpleName();
                        }
                        updateStatus(Text.literal("Failed to sort mods: " + message), 0xFFFF5555, Collections.emptyList());
                        ModClientOrServer.LOGGER.error("Failed to sort mods", cause);
                    } else if (result != null) {
                        Path gameDir = FabricLoader.getInstance().getGameDir().normalize();
                        String outputFolder = formatRelative(gameDir, result.outputDirectory());
                        List<Text> details = buildDetailLines(result, gameDir);
                        updateStatus(Text.literal("Created mod zip sets in " + outputFolder), 0xFF4CAF50, details);
                        if (this.client.player != null) {
                            this.client.player.sendMessage(Text.literal("Mod zip sets created in " + outputFolder).formatted(Formatting.GREEN), false);
                        }
                    }
                });
            });
    }

    private void updateStatus(Text message, int color, List<Text> details) {
        this.statusMessage = message;
        this.statusColor = color;
        this.detailLines = details;
    }

    private List<Text> buildDetailLines(ModSortUtil.SortResult result, Path baseDir) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("Mods analyzed: " + result.totalMods()).formatted(Formatting.GRAY));
        for (ModSortUtil.SideCategory category : ModSortUtil.SideCategory.values()) {
            String label = switch (category) {
                case CLIENT_ONLY -> "Client-only";
                case SERVER_ONLY -> "Server-only";
                case UNIVERSAL -> "Universal";
            };
            int count = result.count(category);
            Path zipPath = result.zipPaths().get(category);
            String location = zipPath != null ? formatRelative(baseDir, zipPath) : "(not created)";
            lines.add(Text.literal(label + ": " + count + " → " + location).formatted(Formatting.DARK_GRAY));
        }
        return Collections.unmodifiableList(lines);
    }

    private static String formatRelative(Path base, Path target) {
        Path normalizedBase = base.normalize();
        Path normalizedTarget = target.normalize();
        try {
            return normalizedBase.relativize(normalizedTarget).toString();
        } catch (IllegalArgumentException ex) {
            return normalizedTarget.toAbsolutePath().toString();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 40, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Sort installed mods into client/server/both zip archives."), this.width / 2, 60, 0xFFBBBBBB);

        if (!statusMessage.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, statusMessage, this.width / 2, 90, statusColor);
            int y = 110;
            for (Text detail : detailLines) {
                context.drawCenteredTextWithShadow(this.textRenderer, detail, this.width / 2, y, 0xFFFFFFFF);
                y += 12;
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
