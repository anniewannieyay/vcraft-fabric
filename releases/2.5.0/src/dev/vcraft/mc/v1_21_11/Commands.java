package dev.vcraft.mc.v1_21_11;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.vcraft.core.State;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.class_2561;

final class Commands {
   private static final SuggestionProvider<FabricClientCommandSource> FRIENDS = (ctx, builder) -> {
      for (State.Profile f : State.friends) {
         builder.suggest(f.name());
      }

      return builder.buildFuture();
   };
   private static final String[] ADMIN_COMMANDS = new String[]{"stats", "list", "kick", "ban", "unban", "rank", "mute", "unmute", "clear", "delete"};
   private static final SuggestionProvider<FabricClientCommandSource> ADMIN = (ctx, builder) -> {
      String typed = builder.getRemaining();
      String[] parts = typed.split(" ", -1);
      if (parts.length <= 1) {
         for (String c : ADMIN_COMMANDS) {
            if (c.startsWith(typed.toLowerCase())) {
               builder.suggest(c);
            }
         }
      } else {
         if (parts.length == 2) {
            SuggestionsBuilder b = builder.createOffset(builder.getStart() + typed.lastIndexOf(32) + 1);

            for (State.Profile f : State.friends) {
               b.suggest(f.name());
            }

            return b.buildFuture();
         }

         if (parts.length == 3 && parts[0].equals("rank")) {
            SuggestionsBuilder b = builder.createOffset(builder.getStart() + typed.lastIndexOf(32) + 1);

            for (String r : new String[]{"owner", "mod", "tester", "none"}) {
               b.suggest(r);
            }

            return b.buildFuture();
         }
      }

      return builder.buildFuture();
   };

   static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommandManager.literal(
                                             "vc"
                                          )
                                          .then(
                                             ClientCommandManager.literal("admin")
                                                .then(
                                                   ClientCommandManager.argument("command", StringArgumentType.greedyString())
                                                      .suggests(ADMIN)
                                                      .executes(ctx -> {
                                                         String[] p = StringArgumentType.getString(ctx, "command").trim().split("\\s+", 2);
                                                         State.admin(p[0].toLowerCase(), p.length > 1 ? p[1] : null);
                                                         return 1;
                                                      })
                                                )
                                          ))
                                       .then(
                                          ClientCommandManager.literal("msg")
                                             .then(
                                                ClientCommandManager.argument("friend", StringArgumentType.word())
                                                   .suggests(FRIENDS)
                                                   .then(ClientCommandManager.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                                                      State.Profile f = friend(ctx);
                                                      if (f != null) {
                                                         State.sendMessage(f.uuid(), StringArgumentType.getString(ctx, "message"), null, null);
                                                      }

                                                      return 1;
                                                   }))
                                             )
                                       ))
                                    .then(
                                       ClientCommandManager.literal("call")
                                          .then(ClientCommandManager.argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
                                             State.Profile f = friend(ctx);
                                             if (f != null) {
                                                State.call(f.uuid());
                                             }

                                             return 1;
                                          }))
                                    ))
                                 .then(
                                    ClientCommandManager.literal("invite")
                                       .then(ClientCommandManager.argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
                                          State.Profile f = friend(ctx);
                                          if (f != null && State.inviteToServer(f.uuid())) {
                                             ((FabricClientCommandSource)ctx.getSource())
                                                .sendFeedback(class_2561.method_43470("Invited " + f.name() + " to this server."));
                                          }

                                          return 1;
                                       }))
                                 ))
                              .then(ClientCommandManager.literal("accept").executes(ctx -> {
                                 State.Invite inv = State.latestInvite();
                                 if (inv == null) {
                                    ((FabricClientCommandSource)ctx.getSource()).sendError(class_2561.method_43470("Nobody is calling you."));
                                 } else {
                                    State.acceptInvite(inv);
                                 }

                                 return 1;
                              })))
                           .then(ClientCommandManager.literal("decline").executes(ctx -> {
                              State.Invite inv = State.latestInvite();
                              if (inv != null) {
                                 State.declineInvite(inv);
                              }

                              return 1;
                           })))
                        .then(ClientCommandManager.literal("hangup").executes(ctx -> {
                           State.send("call_leave", null, null);
                           return 1;
                        })))
                     .then(ClientCommandManager.literal("mute").executes(ctx -> {
                        State.toggleMute();
                        ((FabricClientCommandSource)ctx.getSource())
                           .sendFeedback(class_2561.method_43470(State.muted ? "Microphone muted" : "Microphone unmuted"));
                        return 1;
                     })))
                  .then(ClientCommandManager.literal("deafen").executes(ctx -> {
                     State.toggleDeafen();
                     ((FabricClientCommandSource)ctx.getSource()).sendFeedback(class_2561.method_43470(State.deafened ? "Deafened" : "Undeafened"));
                     return 1;
                  })))
               .then(ClientCommandManager.literal("status").then(ClientCommandManager.argument("status", StringArgumentType.word()).suggests((ctx, b) -> {
                  for (String s : new String[]{"online", "idle", "dnd", "offline"}) {
                     b.suggest(s);
                  }

                  return b.buildFuture();
               }).executes(ctx -> {
                  String s = StringArgumentType.getString(ctx, "status");
                  if ("offline".equals(s)) {
                     s = "invisible";
                  }

                  if (!State.STATUSES.contains(s)) {
                     ((FabricClientCommandSource)ctx.getSource()).sendError(class_2561.method_43470("Use online, idle, dnd or offline."));
                     return 0;
                  } else {
                     State.setStatus(s);
                     ((FabricClientCommandSource)ctx.getSource()).sendFeedback(class_2561.method_43470("Status: " + State.statusLabel(s)));
                     return 1;
                  }
               }))))
            .then(ClientCommandManager.literal("add").then(ClientCommandManager.argument("player", StringArgumentType.word()).executes(ctx -> {
               State.send("friend_request", "name", StringArgumentType.getString(ctx, "player"));
               return 1;
            })))
      );
   }

   /** /vc join and /vc nick (2.5.0): merged into /vc by Brigadier. */
   static void register25(CommandDispatcher<FabricClientCommandSource> dispatcher) {
      dispatcher.register(ClientCommandManager.literal("vc")
         .then(ClientCommandManager.literal("join").then(ClientCommandManager.argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
            State.Profile f = friend(ctx);
            if (f != null) {
               dev.vcraft.core.Social.askToJoin(f.uuid());
            }

            return 1;
         })))
         .then(ClientCommandManager.literal("nick").then(ClientCommandManager.argument("friend", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> nick(ctx, ""))
            .then(ClientCommandManager.argument("nickname", StringArgumentType.greedyString()).executes(ctx -> nick(ctx, StringArgumentType.getString(ctx, "nickname")))))));
   }

   private static int nick(CommandContext<FabricClientCommandSource> ctx, String nick) {
      State.Profile f = friend(ctx);
      if (f != null) {
         String note = dev.vcraft.core.VCraft.config().notes.getOrDefault(f.uuid(), "");
         dev.vcraft.core.Social.setNickname(f.uuid(), nick, note);
         String msg = nick.isBlank() ? dev.vcraft.core.I18n.tf("Cleared your nickname for {0}.", f.name()) : dev.vcraft.core.I18n.tf("{0} is now {1} for you.", f.name(), nick.trim());
         ((FabricClientCommandSource)ctx.getSource()).sendFeedback(class_2561.method_43470(msg));
      }

      return 1;
   }

   private static State.Profile friend(CommandContext<FabricClientCommandSource> ctx) {
      String name = StringArgumentType.getString(ctx, "friend");
      State.Profile f = State.friendByName(name);
      if (f == null) {
         for (State.Profile p : State.friends) {
            String nn = dev.vcraft.core.VCraft.config().nicknames.get(p.uuid());
            if (nn != null && nn.equalsIgnoreCase(name)) {
               f = p;
            }
         }
      }

      if (f == null) {
         ((FabricClientCommandSource)ctx.getSource()).sendError(class_2561.method_43470(name + " isn't on your VCraft friends list."));
      }

      return f;
   }

   private Commands() {
   }
}
