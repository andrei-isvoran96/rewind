package io.github.rewind.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.rewind.core.RewindExecutor;
import io.github.rewind.core.TimelineManager;
import io.github.rewind.network.PreviewSender;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commands for the timeline/rewind system.
 *
 * /timeline rewind <seconds> - Rewind the world state
 * /timeline status - Show buffer status
 * /timeline clear - Clear the buffer
 * /timeline pause - Pause recording
 * /timeline resume - Resume recording
 * /timeline freeze - Freeze timeline (no new frames, rewind still works)
 * /timeline unfreeze - Unfreeze timeline
 * /timeline preview <seconds> - Show 3D diff overlay of potential rewind
 * /timeline preview clear - Clear preview overlay
 */
public class TimelineCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rewind");
    
    private static final int MAX_REWIND_SECONDS = 30;
    private static final int MIN_REWIND_SECONDS = 1;

    /**
     * Register all timeline commands.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("timeline")
                // Use GAMEMASTERS_CHECK for OP level 2 permission
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))

                        // /timeline rewind <seconds>
                        .then(CommandManager.literal("rewind")
                                .then(CommandManager
                                        .argument("seconds",
                                                IntegerArgumentType.integer(MIN_REWIND_SECONDS, MAX_REWIND_SECONDS))
                                        .executes(TimelineCommand::executeRewind)))

                        // /timeline status
                        .then(CommandManager.literal("status")
                                .executes(TimelineCommand::executeStatus))

                        // /timeline clear
                        .then(CommandManager.literal("clear")
                                .executes(TimelineCommand::executeClear))

                        // /timeline pause
                        .then(CommandManager.literal("pause")
                                .executes(TimelineCommand::executePause))

                        // /timeline resume
                        .then(CommandManager.literal("resume")
                                .executes(TimelineCommand::executeResume))

                        // /timeline freeze
                        .then(CommandManager.literal("freeze")
                                .executes(TimelineCommand::executeFreeze))

                        // /timeline unfreeze
                        .then(CommandManager.literal("unfreeze")
                                .executes(TimelineCommand::executeUnfreeze))

                        // /timeline preview <seconds> and preview clear
                        .then(CommandManager.literal("preview")
                                .executes(TimelineCommand::executePreviewClear)
                                .then(CommandManager
                                        .argument("seconds", IntegerArgumentType.integer(MIN_REWIND_SECONDS, MAX_REWIND_SECONDS))
                                        .executes(TimelineCommand::executePreview))));

        LOGGER.info("Timeline commands registered");
    }

    /**
     * Execute the rewind command.
     */
    private static int executeRewind(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        int seconds = IntegerArgumentType.getInteger(context, "seconds");

        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }

        // Check if we have enough frames
        int availableSeconds = manager.getAvailableSeconds();
        if (availableSeconds < seconds) {
            if (availableSeconds == 0) {
                source.sendError(Text.literal("No timeline data available yet. Wait a few seconds."));
                return 0;
            }
            source.sendFeedback(() -> Text.literal(
                    String.format("§eOnly %d seconds available. Rewinding %d seconds instead.",
                            availableSeconds, availableSeconds)),
                    true);
            seconds = availableSeconds;
        }

        // Check if already rewinding
        if (manager.isRewinding()) {
            source.sendError(Text.literal("A rewind is already in progress!"));
            return 0;
        }

        // Notify all players
        final int finalSeconds = seconds;
        source.getServer().getPlayerManager().broadcast(
                Text.literal(String.format("§6[Timeline] §eRewinding %d seconds...", finalSeconds)), false);

        // Execute rewind
        LOGGER.info("Player {} requested rewind of {} seconds",
                source.getName(), seconds);

        RewindExecutor.RewindResult result = RewindExecutor.execute(source.getServer(), seconds);

        // Send result
        if (result.success()) {
            source.sendFeedback(() -> result.toText(), true);

            // Broadcast to all players
            source.getServer().getPlayerManager().broadcast(
                    Text.literal(String.format("§6[Timeline] §aRewind complete! World restored to %d seconds ago.",
                            finalSeconds)),
                    false);
        } else {
            source.sendError(result.toText());
        }

        // Log warnings
        for (String warning : result.warnings()) {
            LOGGER.warn("Rewind warning: {}", warning);
        }

        return result.success() ? 1 : 0;
    }

    /**
     * Execute the status command.
     */
    private static int executeStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }

        String status = manager.getStatusSummary();
        source.sendFeedback(() -> Text.literal("§6" + status.replace("\n", "\n§7")), false);

        return 1;
    }

    /**
     * Execute the clear command.
     */
    private static int executeClear(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }

        if (manager.isRewinding()) {
            source.sendError(Text.literal("Cannot clear while rewinding!"));
            return 0;
        }

        int frameCount = manager.getFrameCount();
        manager.clear();

        source.sendFeedback(() -> Text.literal(
                String.format("§aTimeline buffer cleared. Removed %d frames.", frameCount)), true);

        LOGGER.info("Player {} cleared timeline buffer ({} frames)", source.getName(), frameCount);

        return 1;
    }

    /**
     * Execute the pause command.
     */
    private static int executePause(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }

        if (!manager.isRecording()) {
            source.sendFeedback(() -> Text.literal("§eTimeline recording is already paused."), false);
            return 0;
        }

        manager.setRecording(false);
        source.sendFeedback(() -> Text.literal("§aTimeline recording paused."), true);

        LOGGER.info("Player {} paused timeline recording", source.getName());

        return 1;
    }

    /**
     * Execute the resume command.
     */
    private static int executeResume(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }

        if (manager.isRecording()) {
            source.sendFeedback(() -> Text.literal("§eTimeline recording is already active."), false);
            return 0;
        }

        manager.setRecording(true);
        source.sendFeedback(() -> Text.literal("§aTimeline recording resumed."), true);

        LOGGER.info("Player {} resumed timeline recording", source.getName());

        return 1;
    }

    /**
     * Execute the freeze command.
     */
    private static int executeFreeze(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }
        if (manager.isRewinding()) {
            source.sendError(Text.literal("Cannot freeze while rewinding!"));
            return 0;
        }
        if (manager.isFrozen()) {
            source.sendFeedback(() -> Text.literal("§eTimeline is already frozen."), false);
            return 0;
        }
        manager.freeze();
        source.sendFeedback(() -> Text.literal("§aTimeline frozen. No new changes will be recorded; rewind still works."), true);
        LOGGER.info("Player {} froze the timeline", source.getName());
        return 1;
    }

    /**
     * Execute the unfreeze command.
     */
    private static int executeUnfreeze(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }
        if (!manager.isFrozen()) {
            source.sendFeedback(() -> Text.literal("§eTimeline is not frozen."), false);
            return 0;
        }
        manager.setFrozen(false);
        source.sendFeedback(() -> Text.literal("§aTimeline unfrozen. Recording continues."), true);
        LOGGER.info("Player {} unfroze the timeline", source.getName());
        return 1;
    }

    /**
     * Execute the preview command (show rewind diff overlay).
     */
    private static int executePreview(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendError(Text.literal("Preview is only available to players."));
            return 0;
        }
        TimelineManager manager = TimelineManager.getInstance();
        if (manager == null) {
            source.sendError(Text.literal("Timeline system is not initialized!"));
            return 0;
        }
        if (manager.isRewinding()) {
            source.sendError(Text.literal("Cannot preview while rewinding!"));
            return 0;
        }
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        int availableSeconds = manager.getAvailableSeconds();
        if (availableSeconds < seconds) {
            if (availableSeconds == 0) {
                source.sendError(Text.literal("No timeline data available yet. Wait a few seconds."));
                return 0;
            }
            seconds = availableSeconds;
            final int previewSeconds = seconds;
            source.sendFeedback(() -> Text.literal("§eOnly " + availableSeconds + " seconds available. Showing preview for " + previewSeconds + "s."), false);
        }
        boolean sent = PreviewSender.sendPreviewToPlayer(source.getPlayer(), source.getServer(), seconds);
        if (sent) {
            source.sendFeedback(() -> Text.literal("§aPreview sent. Overlay expires in 10s or use /timeline preview clear."), false);
        } else {
            source.sendError(Text.literal("Failed to build or send preview."));
        }
        return sent ? 1 : 0;
    }

    /**
     * Execute the preview clear command.
     */
    private static int executePreviewClear(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            source.sendError(Text.literal("Preview clear is only available to players."));
            return 0;
        }
        PreviewSender.sendClearToPlayer(source.getPlayer());
        source.sendFeedback(() -> Text.literal("§aPreview cleared."), false);
        return 1;
    }
}
