package dev.vcraft.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.vcraft.core.State;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
//#if v26
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
//#else
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
//#endif

final class Commands {
    private static final SuggestionProvider<FabricClientCommandSource> FRIENDS = (ctx, builder) -> {
        for (State.Profile f : State.friends) builder.suggest(f.name());
        return builder.buildFuture();
    };

    private static final String[] ADMIN_COMMANDS = {"stats", "list", "kick", "ban", "unban", "rank", "mute", "unmute", "clear", "delete"};

    private static final SuggestionProvider<FabricClientCommandSource> ADMIN = (ctx, builder) -> {
        String typed = builder.getRemaining();
        String[] parts = typed.split(" ", -1);
        if (parts.length <= 1) {
            for (String c : ADMIN_COMMANDS) if (c.startsWith(typed.toLowerCase())) builder.suggest(c);
        } else if (parts.length == 2) {
            var b = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
            for (State.Profile f : State.friends) b.suggest(f.name());
            return b.buildFuture();
        } else if (parts.length == 3 && parts[0].equals("rank")) {
            var b = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
            for (String r : new String[]{"owner", "mod", "tester", "none"}) b.suggest(r);
            return b.buildFuture();
        }
        return builder.buildFuture();
    };

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("vc")
                .then(literal("admin").then(argument("command", StringArgumentType.greedyString()).suggests(ADMIN).executes(ctx -> {
                    String[] p = StringArgumentType.getString(ctx, "command").trim().split("\\s+", 2);
                    State.admin(p[0].toLowerCase(), p.length > 1 ? p[1] : null);
                    return 1;
                })))
                .then(literal("msg").then(argument("friend", StringArgumentType.word()).suggests(FRIENDS)
                        .then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            State.Profile f = friend(ctx);
                            if (f != null) State.sendMessage(f.uuid(), StringArgumentType.getString(ctx, "message"), null, null);
                            return 1;
                        }))))
                .then(literal("call").then(argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
                    State.Profile f = friend(ctx);
                    if (f != null) State.call(f.uuid());
                    return 1;
                })))
                .then(literal("invite").then(argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
                    State.Profile f = friend(ctx);
                    if (f != null && State.inviteToServer(f.uuid())) {
                        ctx.getSource().sendFeedback(Component.literal("Invited " + f.name() + " to this server."));
                    }
                    return 1;
                })))
                .then(literal("accept").executes(ctx -> {
                    State.Invite inv = State.latestInvite();
                    if (inv == null) ctx.getSource().sendError(Component.literal("Nobody is calling you."));
                    else State.acceptInvite(inv);
                    return 1;
                }))
                .then(literal("decline").executes(ctx -> {
                    State.Invite inv = State.latestInvite();
                    if (inv != null) State.declineInvite(inv);
                    return 1;
                }))
                .then(literal("hangup").executes(ctx -> {
                    State.send("call_leave", null, null);
                    return 1;
                }))
                .then(literal("mute").executes(ctx -> {
                    State.toggleMute();
                    ctx.getSource().sendFeedback(Component.literal(State.muted ? "Microphone muted" : "Microphone unmuted"));
                    return 1;
                }))
                .then(literal("deafen").executes(ctx -> {
                    State.toggleDeafen();
                    ctx.getSource().sendFeedback(Component.literal(State.deafened ? "Deafened" : "Undeafened"));
                    return 1;
                }))
                .then(literal("status").then(argument("status", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            for (String s : new String[]{"online", "idle", "dnd", "offline"}) b.suggest(s);
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            String s = StringArgumentType.getString(ctx, "status");
                            if ("offline".equals(s)) s = "invisible";
                            if (!State.STATUSES.contains(s)) {
                                ctx.getSource().sendError(Component.literal("Use online, idle, dnd or offline."));
                                return 0;
                            }
                            State.setStatus(s);
                            ctx.getSource().sendFeedback(Component.literal("Status: " + State.statusLabel(s)));
                            return 1;
                        })))
                .then(literal("add").then(argument("player", StringArgumentType.word()).executes(ctx -> {
                    State.send("friend_request", "name", StringArgumentType.getString(ctx, "player"));
                    return 1;
                }))));
    }

    private static State.Profile friend(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "friend");
        State.Profile f = State.friendByName(name);
        if (f == null) ctx.getSource().sendError(Component.literal(name + " isn't on your VCraft friends list."));
        return f;
    }

    private Commands() {
    }
}
