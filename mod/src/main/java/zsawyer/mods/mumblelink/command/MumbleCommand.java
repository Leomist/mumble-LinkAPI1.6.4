/*
 * Copyright (C) 2013, zsawyer <zsawyer@users.sourceforge.net>
 * Modifications Copyright (C) 2014, Leomist
 *
 * All rights reserved.
 */
package zsawyer.mods.mumblelink.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import zsawyer.mods.mumblelink.MumbleLinkMod;
import zsawyer.mods.mumblelink.handler.TickHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Client-side diagnostics and control command for MumbleLink. */
public final class MumbleCommand extends CommandBase {

    private static final List<String> SUBCOMMANDS = Collections.unmodifiableList(
            Arrays.asList("help", "status", "values", "stats", "checks", "diag", "all",
                    "banner", "reconnect", "test"));

    @Override
    public String getCommandName() {
        return "mumble";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/mumble <help|status|values|stats|checks|diag|all|banner|reconnect|test>";
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS.toArray(new String[SUBCOMMANDS.size()]));
        }
        if (args.length >= 2 && "test".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("chat-delivery-check");
        }
        return null;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        TickHandler handler = MumbleLinkMod.getTickHandler();
        if (handler == null) {
            send(sender, "§c[MumbleLink] Tick handler is not available.");
            return;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "help";

        if ("help".equals(sub)) {
            sendHelp(sender, handler);
            return;
        }
        if ("status".equals(sub)) {
            sendStatus(sender, handler.snapshot(), handler.getChatPrefix());
            return;
        }
        if ("values".equals(sub)) {
            sendValues(sender, handler.snapshot(), handler.getChatPrefix());
            return;
        }
        if ("stats".equals(sub)) {
            sendStats(sender, handler.snapshot(), handler.getChatPrefix());
            return;
        }
        if ("checks".equals(sub)) {
            sendChecks(sender, handler.snapshot(), handler.getChatPrefix());
            return;
        }
        if ("diag".equals(sub) || "all".equals(sub)) {
            sendDiagnostics(sender, handler.snapshot(), handler.getChatPrefix());
            return;
        }
        if ("banner".equals(sub)) {
            handler.resendWorldJoinBanner();
            send(sender, handler.getChatPrefix() + "§aJoin banner re-queued.");
            return;
        }
        if ("reconnect".equals(sub)) {
            handler.forceReconnect();
            send(sender, handler.getChatPrefix() + "§eReconnect requested.");
            return;
        }
        if ("test".equals(sub)) {
            String message = "chat-delivery-check";
            if (args.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) sb.append(' ');
                    sb.append(args[i]);
                }
                message = sb.toString();
            }
            handler.enqueueCommandMessage(handler.getChatPrefix() + "§fTest: " + message + " §7(ts=" + System.currentTimeMillis() + ")");
            send(sender, handler.getChatPrefix() + "§aTest message queued.");
            return;
        }

        send(sender, "§cUnknown subcommand. " + getCommandUsage(sender));
    }

    private static void sendHelp(ICommandSender sender, TickHandler handler) {
        String p = handler.getChatPrefix();
        send(sender, p + "§eUsage: " + getUsageText());
        send(sender, p + "§7help      - list commands");
        send(sender, p + "§7status    - high-level link/chat status");
        send(sender, p + "§7values    - current context/player/position/front vectors");
        send(sender, p + "§7stats     - counters (joins, retries, sends, failures)");
        send(sender, p + "§7checks    - pass/fail checks for common setup issues");
        send(sender, p + "§7diag/all  - full diagnostics dump");
        send(sender, p + "§7banner    - resend world-join banner");
        send(sender, p + "§7reconnect - force re-open link on next tick");
        send(sender, p + "§7test [t]  - queue test chat message");
    }

    private static String getUsageText() {
        return "/mumble <help|status|values|stats|checks|diag|all|banner|reconnect|test>";
    }

    private static void sendStatus(ICommandSender sender, TickHandler.Snapshot s, String p) {
        send(sender, p + "§fLink: " + yesNo(s.linkActive) + "  §7inWorld=" + s.inWorld + "  pending=" + s.pendingMessages);
        send(sender, p + "§fContext: §7" + s.lastKnownContext + "  §fPlayer: §7" + s.lastKnownPlayer);
        send(sender, p + "§fChat mode: §7" + s.chatDeliveryMode);
    }

    private static void sendValues(ICommandSender sender, TickHandler.Snapshot s, String p) {
        send(sender, p + "§fContext: §7" + s.lastKnownContext);
        send(sender, p + "§fPlayer: §7" + s.lastKnownPlayer);
        send(sender, p + "§fPosition: §7[" + s.lastPosX + ", " + s.lastPosY + ", " + s.lastPosZ + "]");
        send(sender, p + "§fFront: §7[" + s.lastFrontX + ", " + s.lastFrontY + ", " + s.lastFrontZ + "]");
    }

    private static void sendStats(ICommandSender sender, TickHandler.Snapshot s, String p) {
        send(sender, p + "§fTicks: §7" + s.tickCount + "  joins=" + s.worldJoinCount + " leaves=" + s.worldLeaveCount);
        send(sender, p + "§fLink opens: §7attempts=" + s.linkOpenAttempts + " successes=" + s.linkOpenSuccesses + " losses=" + s.linkLossCount);
        send(sender, p + "§fChat: §7sent=" + s.chatSentCount + " failures=" + s.chatSendFailures + " retries=" + s.chatRetryCount + " pending=" + s.pendingMessages);
        send(sender, p + "§fBanners: §7" + s.joinBannerCount + "  writeFailures=" + s.linkWriteFailures);
    }

    private static void sendChecks(ICommandSender sender, TickHandler.Snapshot s, String p) {
        List<String> checks = new ArrayList<String>();
        checks.add(check("In world loaded", s.inWorld));
        checks.add(check("Link active", s.linkActive));
        checks.add(check("Context detected", s.lastKnownContext != null && s.lastKnownContext.length() > 0 && !"mumblelink|unknown".equals(s.lastKnownContext)));
        checks.add(check("Player identity available", s.lastKnownPlayer != null && s.lastKnownPlayer.length() > 0));
        checks.add(check("No pending chat backlog", s.pendingMessages == 0));
        checks.add(check("No chat send failures", s.chatSendFailures == 0));
        checks.add(check("No link write failures", s.linkWriteFailures == 0));
        send(sender, p + "§eChecks:");
        for (String c : checks) {
            send(sender, p + c);
        }
    }

    private static String check(String name, boolean pass) {
        return (pass ? "§aPASS§r " : "§cFAIL§r ") + name;
    }

    private static void sendDiagnostics(ICommandSender sender, TickHandler.Snapshot s, String p) {
        send(sender, p + "§eDiagnostics begin");
        List<String> lines = s.toDiagnosticLines();
        for (String line : lines) {
            send(sender, p + "§7" + line);
        }
        send(sender, p + "§eDiagnostics end");
    }

    private static String yesNo(boolean v) {
        return v ? "§aACTIVE§r" : "§cINACTIVE§r";
    }

    /** Sends one chat line to command sender via runtime-compatible reflection. */
    private static void send(ICommandSender sender, String text) {
        try {
            Method[] methods = sender.getClass().getMethods();
            for (Method m : methods) {
                if (m.getParameterTypes().length == 1 &&
                        ("addChatMessage".equals(m.getName()) || "sendChatToPlayer".equals(m.getName())
                                || "func_145747_a".equals(m.getName()))) {
                    Class<?> p = m.getParameterTypes()[0];
                    Object arg = toChatArg(p, text);
                    m.invoke(sender, arg);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Object toChatArg(Class<?> paramType, String text) throws Exception {
        if (String.class.equals(paramType)) return text;
        String typeName = paramType.getName();
        if ("net.minecraft.util.ChatMessageComponent".equals(typeName)) {
            try {
                Method m = paramType.getMethod("createFromText", String.class);
                return m.invoke(null, text);
            } catch (NoSuchMethodException ignored) { }
            try {
                Method m = paramType.getMethod("createFromString", String.class);
                return m.invoke(null, text);
            } catch (NoSuchMethodException ignored) { }
            Method m = paramType.getMethod("func_111077_e", String.class);
            return m.invoke(null, text);
        }
        if ("net.minecraft.util.IChatComponent".equals(typeName)) {
            Class<?> chatTextClass = Class.forName("net.minecraft.util.ChatComponentText");
            Constructor<?> ctor = chatTextClass.getConstructor(String.class);
            return ctor.newInstance(text);
        }
        Constructor<?> c = paramType.getConstructor(String.class);
        return c.newInstance(text);
    }
}
