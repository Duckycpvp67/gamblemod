package nl.gamble;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gamble helper voor DonutSMP.
 * Bij "X paid you $5M" verschijnt in de chat: [WIN] [LOSE] <game>
 * WIN  = betaalt automatisch 2x uit
 * LOSE = inzet blijft bij jou
 * Buiten de limieten = [REFUND] knop
 * Voor Minecraft 1.21.11 (Yarn, Fabric API 0.14x+, Java 21).
 */
public class GambleMod implements ClientModInitializer {

    private static final Pattern PAID = Pattern.compile("(\\w+) paid you \\$([\\d.,]+)([KMBkmb]?)");

    private double profit = 0, loss = 0;

    @Override
    public void onInitializeClient() {

        // 1) Betaling opvangen en knoppen tonen
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            Matcher m = PAID.matcher(msg.getString());
            if (!m.find()) return;
            String player = m.group(1);
            double amt = toNumber(m.group(2), m.group(3));
            showButtons(player, amt);
        });

        // 2) Client-commands die door de knoppen worden aangeroepen
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(ClientCommandManager.literal("gwin")
                .then(ClientCommandManager.argument("p", StringArgumentType.word())
                .then(ClientCommandManager.argument("bet", DoubleArgumentType.doubleArg())
                .executes(c -> {
                    String p = StringArgumentType.getString(c, "p");
                    double bet = DoubleArgumentType.getDouble(c, "bet");
                    long payout = (long) (bet * 2);
                    loss += bet;
                    msg(Text.literal(p + " won. You pay out $" + fmt(payout)).formatted(Formatting.RED));
                    pay(p, payout);
                    return 1;
                }))));

            d.register(ClientCommandManager.literal("glose")
                .then(ClientCommandManager.argument("p", StringArgumentType.word())
                .then(ClientCommandManager.argument("bet", DoubleArgumentType.doubleArg())
                .executes(c -> {
                    String p = StringArgumentType.getString(c, "p");
                    double bet = DoubleArgumentType.getDouble(c, "bet");
                    profit += bet;
                    msg(Text.literal(p + " lost. +$" + fmt(bet)).formatted(Formatting.GREEN));
                    return 1;
                }))));

            d.register(ClientCommandManager.literal("grefund")
                .then(ClientCommandManager.argument("p", StringArgumentType.word())
                .then(ClientCommandManager.argument("bet", DoubleArgumentType.doubleArg())
                .executes(c -> {
                    String p = StringArgumentType.getString(c, "p");
                    double bet = DoubleArgumentType.getDouble(c, "bet");
                    msg(Text.literal("Refund " + p + " $" + fmt(bet)).formatted(Formatting.YELLOW));
                    pay(p, (long) bet);
                    return 1;
                }))));
        });

        // 3) HUD met profit / loss
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of("gamble", "stats"), (ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            ctx.drawTextWithShadow(mc.textRenderer, "§aProfit: $" + fmt(profit), 6, 6, 0xFFFFFF);
            ctx.drawTextWithShadow(mc.textRenderer, "§cLoss: $" + fmt(loss), 6, 17, 0xFFFFFF);
            double net = profit - loss;
            ctx.drawTextWithShadow(mc.textRenderer, (net >= 0 ? "§a" : "§c") + "Net: $" + fmt(net), 6, 28, 0xFFFFFF);
        });
    }

    private void showButtons(String p, double amt) {
        String game = classify(amt);
        String args = " " + p + " " + (long) amt;
        MutableText line = Text.empty();
        if (game == null) {
            line.append(btn("[REFUND]", Formatting.YELLOW, "grefund" + args));
            line.append(Text.literal(" buiten limiet").formatted(Formatting.GRAY));
        } else {
            line.append(btn("[WIN]", Formatting.AQUA, "gwin" + args));
            line.append(Text.literal(" "));
            line.append(btn("[LOSE]", Formatting.RED, "glose" + args));
            line.append(Text.literal("  " + game).formatted(Formatting.LIGHT_PURPLE));
        }
        msg(line);
    }

    /** Game bepalen op basis van het bedrag. null = buiten limiet */
    private String classify(double amt) {
        long tenths = Math.round(amt / 100_000.0);   // 5.1M -> 51
        int d = (int) (tenths % 10);
        if (d == 1 && amt >= 5_100_000 && amt <= 501_000_000) return "BJ";
        if (d == 2 && amt >= 20_200_000 && amt <= 502_000_000) return "Arrow Game 2x";
        if (d == 0 && amt >= 5_000_000 && amt <= 500_000_000) return "Paper Game";
        return null;
    }

    private MutableText btn(String label, Formatting color, String cmd) {
        return Text.literal(label).setStyle(Style.EMPTY.withColor(color).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand(cmd)));
    }

    private void pay(String p, long amount) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null)
            mc.getNetworkHandler().sendChatCommand("pay " + p + " " + amount);
    }

    private void msg(Text t) {
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(t);
    }

    private static double toNumber(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        return switch (suffix.toUpperCase()) {
            case "K" -> v * 1_000;
            case "M" -> v * 1_000_000;
            case "B" -> v * 1_000_000_000;
            default -> v;
        };
    }

    private static String fmt(double v) {
        double a = Math.abs(v);
        String s = a >= 1e9 ? String.format("%.2fB", a / 1e9)
                 : a >= 1e6 ? String.format("%.1fM", a / 1e6)
                 : a >= 1e3 ? String.format("%.0fK", a / 1e3)
                 : String.valueOf((long) a);
        return (v < 0 ? "-" : "") + s;
    }
}
