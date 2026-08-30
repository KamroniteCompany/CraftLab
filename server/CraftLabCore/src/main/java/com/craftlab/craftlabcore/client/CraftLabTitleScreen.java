package com.craftlab.craftlabcore.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.ModListScreen;
import org.slf4j.Logger;

/**
 * Écran d'accueil CraftLab, remplaçant le {@code TitleScreen} vanilla (voir
 * {@link ClientForgeEvents}). Étend {@link Screen} directement — pas {@code TitleScreen} — pour
 * garder un contrôle total sur les widgets affichés (aucun Singleplayer/Multiplayer/Realms
 * vanilla) sans dépendre des champs privés de {@code TitleScreen} ni risquer une substitution
 * en boucle (un {@code CraftLabTitleScreen} n'est jamais lui-même une instance de
 * {@code TitleScreen}).
 *
 * Le panorama animé (fond d'écran) reste celui de Minecraft : {@link #renderPanorama} est
 * hérité tel quel de {@code Screen}, aucune image externe n'est nécessaire pour rester cohérent
 * visuellement avec le jeu.
 */
@OnlyIn(Dist.CLIENT)
public class CraftLabTitleScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component TITLE = Component.literal("CraftLab");
    private static final Component SUBTITLE = Component.literal("Serveur communautaire Minecraft");
    private static final Component FOOTER_BRAND = Component.literal("CraftLab");
    private static final Component FOOTER_VERSION = Component.literal("Minecraft 1.21.1");

    public CraftLabTitleScreen() {
        super(TITLE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        // Ancré depuis le centre vertical plutôt que depuis une position fixe, pour rester
        // utilisable sur d'autres résolutions que 1280x720 (voir docs/launcher.md côté launcher).
        int playY = this.height / 2 + 8;

        this.addRenderableWidget(
            Button.builder(Component.literal("Jouer"), button -> this.onPlay())
                .bounds(centerX - 100, playY, 200, 24)
                .build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Mods"), button -> this.onMods())
                .bounds(centerX - 100, playY + 32, 96, 20)
                .build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Paramètres"), button -> this.onSettings())
                .bounds(centerX + 4, playY + 32, 96, 20)
                .build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Quitter"), button -> this.onQuit())
                .bounds(centerX - 100, playY + 60, 200, 20)
                .build()
        );

        LOGGER.info("[CraftLab] Écran CraftLab chargé.");
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderPanorama(guiGraphics, partialTick);

        // Voile sombre semi-transparent par-dessus le panorama : lisibilité du texte/boutons
        // et identité visuelle "sombre/moderne" CraftLab, sans texture additionnelle.
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101014, 0xF0101014);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 64;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, titleY, 0);
        guiGraphics.pose().scale(3.0F, 3.0F, 1.0F);
        guiGraphics.drawCenteredString(this.font, TITLE, 0, 0, 0xFFFFFF);
        guiGraphics.pose().popPose();

        guiGraphics.drawCenteredString(this.font, SUBTITLE, centerX, titleY + 28, 0xA0A0A0);

        guiGraphics.drawString(this.font, FOOTER_BRAND, 4, this.height - 20, 0xFFFFFF);
        guiGraphics.drawString(this.font, FOOTER_VERSION, 4, this.height - 10, 0xA0A0A0);
    }

    private void onPlay() {
        LOGGER.info("[CraftLab] Bouton Jouer sélectionné.");
        String target = ServerTarget.resolve();
        ServerAddress serverAddress = ServerAddress.parseString(target);
        ServerData serverData = new ServerData("CraftLab", target, ServerData.Type.OTHER);
        // Même méthode native que celle utilisée par le bouton "Rejoindre" vanilla d'un serveur
        // de la liste multijoueur (et par Quick Play en interne) : aucune logique réseau
        // ré-implémentée ici. "this" comme écran parent (pas un nouveau TitleScreen) garantit
        // qu'une erreur de connexion ou une déconnexion ultérieure ramène le joueur exactement
        // sur cette instance de CraftLabTitleScreen — voir ClientForgeEvents pour le détail.
        ConnectScreen.startConnecting(this, this.minecraft, serverAddress, serverData, false, null);
    }

    private void onMods() {
        LOGGER.info("[CraftLab] Ouverture de la liste des mods.");
        this.minecraft.setScreen(new ModListScreen(this));
    }

    private void onSettings() {
        LOGGER.info("[CraftLab] Ouverture des paramètres.");
        this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options));
    }

    private void onQuit() {
        LOGGER.info("[CraftLab] Fermeture du client.");
        this.minecraft.stop();
    }
}
