package com.ascargon.rocketshow.midi;

import com.ascargon.rocketshow.composition.Composition;
import com.ascargon.rocketshow.composition.CompositionService;
import com.ascargon.rocketshow.composition.Set;
import com.ascargon.rocketshow.composition.SetComposition;
import com.ascargon.rocketshow.composition.SetService;
import com.ascargon.rocketshow.play.CompositionPlayer;
import com.ascargon.rocketshow.play.PlayerService;
import com.ascargon.rocketshow.settings.Settings;
import com.ascargon.rocketshow.settings.SettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.sound.midi.MidiMessage;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Follow an incoming MIDI timecode.
 * <p>
 * Incoming messages are decoded ({@link MidiTimecodeDecoder}) into absolute positions and smoothed
 * into a continuous position ({@link MidiTimecodeClock}). A control loop then maps that position
 * onto a composition - either the selected one or one of the compositions in the current set,
 * depending on the configured mapping - and keeps the player on it: it selects and starts the
 * composition when the timecode enters its window, pauses when the timecode stops, re-locates when
 * the master jumps, and hands the position to the player on every tick so the lighting designer, the
 * action triggers and the media pipeline all follow the master.
 * <p>
 * Transport commands (load/play/pause/seek) are run on a separate single thread: they can take a
 * while (loading and prerolling a composition) and must not stall the control loop.
 */
@Service
public class DefaultMidiTimecodeSlaveService implements MidiTimecodeSlaveService {

    private final static Logger logger = LoggerFactory.getLogger(DefaultMidiTimecodeSlaveService.class);

    // How often the incoming timecode is compared against the player. Well above the rate at which
    // timecode positions arrive (one every two frames), so the media correction stays responsive.
    private final static long CONTROL_PERIOD_MILLIS = 20;

    // How often the setting is re-checked while this device is not a timecode slave at all. The
    // control loop has nothing to do then, and a device that sits idle for hours should not be woken
    // 50 times a second for it.
    private final static long IDLE_PERIOD_MILLIS = 1_000;

    private final SettingsService settingsService;
    private final CompositionService compositionService;
    private final SetService setService;

    // Lazy to avoid a circular dependency: the player service is built on top of the MIDI services.
    private final PlayerService playerService;

    private final MidiTimecodeDecoder decoder = new MidiTimecodeDecoder();
    private final MidiTimecodeClock clock = new MidiTimecodeClock();

    private final ScheduledExecutorService controlScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService transportExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean transportBusy = new AtomicBoolean(false);

    // Is the slave currently switched on (the setting is active)? Also paces the control loop, and
    // is read by isLocked() from other threads.
    private volatile boolean active = false;

    // Is the master currently rolling and the player following it?
    private boolean rolling = false;

    // Only complain once per lock about a set mapping without a set
    private boolean missingSetLogged = false;

    public DefaultMidiTimecodeSlaveService(SettingsService settingsService, CompositionService compositionService,
                                           SetService setService, @Lazy PlayerService playerService) {
        this.settingsService = settingsService;
        this.compositionService = compositionService;
        this.setService = setService;
        this.playerService = playerService;
    }

    @PostConstruct
    public void init() {
        scheduleNextTick();
    }

    /**
     * Run the control loop at its own pace instead of at a fixed rate: fast enough to correct the
     * media while a timecode is being followed, and barely at all while it is switched off.
     */
    private void scheduleNextTick() {
        if (controlScheduler.isShutdown()) {
            return;
        }

        try {
            controlScheduler.schedule(this::runTick, active ? CONTROL_PERIOD_MILLIS : IDLE_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down
        }
    }

    private void runTick() {
        try {
            tick();
        } finally {
            scheduleNextTick();
        }
    }

    @Override
    public synchronized void processMidiMessage(MidiMessage midiMessage) {
        if (settingsService.getSettings().getMidiTimecodeMode() != MidiTimecodeMode.SLAVE) {
            return;
        }

        long nowNanos = System.nanoTime();

        decoder.process(midiMessage).ifPresent(result ->
                clock.observe(result.positionMillis(), nowNanos, result.locate()));
    }

    @Override
    public boolean isLocked() {
        return active && clock.isRunning(System.nanoTime());
    }

    @Override
    public long getTimecodeMillis() {
        long nowNanos = System.nanoTime();

        if (!active || !clock.isRunning(nowNanos)) {
            return -1;
        }

        return clock.getPositionMillis(nowNanos);
    }

    /**
     * The position inside a composition that an incoming timecode position addresses.
     *
     * @param compositionName    the composition to be playing
     * @param positionMillis     the position inside that composition
     * @param setCompositionIndex the index within the current set, or -1 outside the set mapping
     */
    private record Target(String compositionName, long positionMillis, int setCompositionIndex) {
    }

    private void tick() {
        try {
            Settings settings = settingsService.getSettings();

            if (settings.getMidiTimecodeMode() != MidiTimecodeMode.SLAVE) {
                if (active) {
                    deactivate();
                }

                return;
            }

            if (!active) {
                logger.info("Following the MIDI timecode received on the MIDI input device");
                active = true;
            }

            long nowNanos = System.nanoTime();

            if (!clock.isRunning(nowNanos)) {
                if (rolling) {
                    logger.info("The incoming MIDI timecode stopped");
                    rolling = false;
                    missingSetLogged = false;
                    submitTransport(this::pausePlayback);
                }

                return;
            }

            // A positive offset makes this device play later than the incoming timecode, which is
            // how the latency of the MIDI transport and of the audio output is compensated.
            long timecodeMillis = clock.getPositionMillis(nowNanos) - getSlaveOffsetMillis(settings);
            Target target = resolveTarget(timecodeMillis, settings);

            if (target == null) {
                // The timecode is outside every composition we know about
                if (rolling) {
                    rolling = false;
                    submitTransport(this::pausePlayback);
                }

                return;
            }

            playerService.setExternalTimecodePositionMillis(target.positionMillis());

            if (transportBusy.get()) {
                // A transport command (loading, starting, pausing) is still running. Leave the
                // pipeline alone until it is done, the next tick picks up from there.
                return;
            }

            if (!rolling
                    || !target.compositionName().equals(playerService.getCompositionName())
                    || playerService.getPlayState() != CompositionPlayer.PlayState.PLAYING) {

                rolling = true;
                // Starting locates to the master anyway
                clock.consumeJump();
                submitTransport(() -> startPlayback(target));
                return;
            }

            if (clock.consumeJump()) {
                // The master re-located or looped; catching up by trimming would take far too long
                submitTransport(() -> playerService.seek(target.positionMillis()));
                return;
            }

            playerService.syncToTimecode(target.positionMillis());
        } catch (Exception e) {
            logger.error("Could not follow the incoming MIDI timecode", e);
        }
    }

    /**
     * Map an absolute timecode position onto a composition and a position inside it, or null if the
     * timecode does not address any composition.
     */
    private Target resolveTarget(long timecodeMillis, Settings settings) {
        if (settings.getMidiTimecodeSlaveMapping() == MidiTimecodeSlaveMapping.SET) {
            return resolveTargetInSet(timecodeMillis);
        }

        Composition composition = playerService.getCurrentComposition();

        if (composition == null) {
            return null;
        }

        long positionMillis = timecodeMillis - composition.getTimecodeStartMillis();

        if (positionMillis < 0) {
            return null;
        }

        // A composition without any media (only actions or a designer project) has no duration, so
        // it is not bounded by one either
        if (composition.getDurationMillis() > 0 && positionMillis >= composition.getDurationMillis()) {
            return null;
        }

        return new Target(composition.getName(), positionMillis, -1);
    }

    private Target resolveTargetInSet(long timecodeMillis) {
        Set currentSet = setService.getCurrentSet();

        if (currentSet == null) {
            if (!missingSetLogged) {
                logger.warn("The MIDI timecode is mapped to a set, but no set is loaded");
                missingSetLogged = true;
            }

            return null;
        }

        List<SetComposition> setCompositionList = currentSet.getSetCompositionList();

        for (int index = 0; index < setCompositionList.size(); index++) {
            SetComposition setComposition = setCompositionList.get(index);
            long startMillis = setComposition.getTimecodeStartMillis();

            // Without a duration the composition has no window in the set's timeline
            if (setComposition.getDurationMillis() <= 0) {
                continue;
            }

            if (timecodeMillis >= startMillis && timecodeMillis < startMillis + setComposition.getDurationMillis()) {
                return new Target(setComposition.getName(), timecodeMillis - startMillis, index);
            }
        }

        return null;
    }

    /**
     * Select (if needed) and start the composition the timecode is in, then immediately re-locate to
     * where the master has moved on to while it was being loaded.
     */
    private void startPlayback(Target target) throws Exception {
        if (!target.compositionName().equals(playerService.getCompositionName())) {
            logger.info("Selecting composition '{}' for the incoming MIDI timecode", target.compositionName());

            if (target.setCompositionIndex() >= 0) {
                setService.setCurrentCompositionIndex(target.setCompositionIndex());
            }

            // Don't fall back to the default composition in between, the timecode is still running
            playerService.setComposition(compositionService.getComposition(target.compositionName()), false, false);
        }

        playerService.seek(target.positionMillis());
        playerService.play();

        // Loading and prerolling took a moment, so the master has moved on. Locate once more instead
        // of leaving it to the drift correction, which would have to seek anyway.
        Target currentTarget = resolveTarget(
                clock.getPositionMillis(System.nanoTime()) - getSlaveOffsetMillis(settingsService.getSettings()),
                settingsService.getSettings());

        if (currentTarget != null && currentTarget.compositionName().equals(target.compositionName())) {
            playerService.setExternalTimecodePositionMillis(currentTarget.positionMillis());
            playerService.seek(currentTarget.positionMillis());
        }
    }

    /**
     * Pause rather than stop: the pipeline stays prerolled, so the composition is back in sync
     * within a few milliseconds when the master rolls again.
     */
    private void pausePlayback() throws Exception {
        if (playerService.getPlayState() != CompositionPlayer.PlayState.PLAYING) {
            return;
        }

        playerService.pause();

        if (playerService.getPlayState() == CompositionPlayer.PlayState.PLAYING) {
            // Pausing was refused (the hardware H.265 decoder cannot pause/resume), so the only way
            // to stop following a timecode that is no longer there is to stop altogether
            playerService.stop(false);
        }
    }

    private void deactivate() {
        logger.info("No longer following the MIDI timecode");

        active = false;
        rolling = false;
        missingSetLogged = false;

        clock.reset();
        decoder.reset();
        playerService.setExternalTimecodePositionMillis(-1);
    }

    private static long getSlaveOffsetMillis(Settings settings) {
        return settings.getMidiTimecodeSlaveOffsetMillis() == null ? 0 : settings.getMidiTimecodeSlaveOffsetMillis();
    }

    private interface TransportCommand {
        void run() throws Exception;
    }

    /**
     * Run a transport command off the control loop, dropping it while another one is still running
     * (the control loop re-issues what is still needed on its next tick anyway).
     */
    private void submitTransport(TransportCommand command) {
        if (!transportBusy.compareAndSet(false, true)) {
            return;
        }

        transportExecutor.execute(() -> {
            try {
                command.run();
            } catch (Exception e) {
                logger.error("Could not execute a transport command for the incoming MIDI timecode", e);
            } finally {
                transportBusy.set(false);
            }
        });
    }

    @PreDestroy
    public void close() {
        controlScheduler.shutdownNow();
        transportExecutor.shutdownNow();
    }

}
