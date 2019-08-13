package com.ascargon.rocketshow.composition;

import com.ascargon.rocketshow.CapabilitiesService;
import com.ascargon.rocketshow.PlayerService;
import com.ascargon.rocketshow.SettingsService;
import com.ascargon.rocketshow.api.ActivityNotificationAudioService;
import com.ascargon.rocketshow.api.ActivityNotificationMidiService;
import com.ascargon.rocketshow.api.NotificationService;
import com.ascargon.rocketshow.audio.AudioBus;
import com.ascargon.rocketshow.audio.AudioCompositionFile;
import com.ascargon.rocketshow.audio.AudioService;
import com.ascargon.rocketshow.gstreamer.GstApi;
import com.ascargon.rocketshow.lighting.LightingService;
import com.ascargon.rocketshow.lighting.Midi2LightingConvertService;
import com.ascargon.rocketshow.lighting.designer.DesignerService;
import com.ascargon.rocketshow.lighting.designer.Project;
import com.ascargon.rocketshow.midi.*;
import com.ascargon.rocketshow.video.VideoCompositionFile;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.BaseSink;
import org.freedesktop.gstreamer.elements.PlayBin;
import org.freedesktop.gstreamer.elements.URIDecodeBin;
import org.freedesktop.gstreamer.lowlevel.GType;
import org.freedesktop.gstreamer.lowlevel.GValueAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.midi.InvalidMidiDataException;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.lang.Math;

/**
 * Handle the playing of a single composition.
 */
@Service
public class CompositionPlayer {

    private final static Logger logger = LoggerFactory.getLogger(CompositionPlayer.class);

    private final String uuid = String.valueOf(UUID.randomUUID());

    public enum PlayState {
        PLAYING, // Is the composition playing?
        PAUSED, // Is the composition paused?
        STOPPING, // Is the composition being stopped?
        STOPPED, // Is the composition stopped?
        LOADING, // Is the composition waiting for all files to be loaded to start playing?
        LOADED // Has the composition finished loading all files?
    }

    private final NotificationService notificationService;
    private final ActivityNotificationMidiService activityNotificationMidiService;
    private final PlayerService playerService;
    private final SettingsService settingsService;
    private final CapabilitiesService capabilitiesService;
    private final ActivityNotificationAudioService activityNotificationAudioService;
    private final SetService setService;
    private final Midi2LightingConvertService midi2LightingConvertService;
    private final LightingService lightingService;
    private final MidiDeviceOutService midiDeviceOutService;
    private final AudioService audioService;
    private final DesignerService designerService;

    private Composition composition;
    private PlayState playState = PlayState.STOPPED;
    private long startPosition = 0;

    private final MidiMapping midiMapping = new MidiMapping();

    // Is this the default composition?
    private boolean isDefaultComposition = false;

    // Is this composition played as a sample?
    private boolean isSample = false;

    // The gstreamer pipeline, used to sync all files in this composition
    private Pipeline pipeline;

    // All MIDI routers
    private List<MidiRouter> midiRouterList = new ArrayList<>();

    // The designer project, if available
    private Project project;

    public CompositionPlayer(NotificationService notificationService, ActivityNotificationMidiService activityNotificationMidiService, PlayerService playerService, SettingsService settingsService, CapabilitiesService capabilitiesService, ActivityNotificationAudioService activityNotificationAudioService, SetService setService, Midi2LightingConvertService midi2LightingConvertService, LightingService lightingService, MidiDeviceOutService midiDeviceOutService, AudioService audioService, DesignerService designerService) {
        this.notificationService = notificationService;
        this.activityNotificationMidiService = activityNotificationMidiService;
        this.playerService = playerService;
        this.settingsService = settingsService;
        this.capabilitiesService = capabilitiesService;
        this.activityNotificationAudioService = activityNotificationAudioService;
        this.setService = setService;
        this.midi2LightingConvertService = midi2LightingConvertService;
        this.lightingService = lightingService;
        this.midiDeviceOutService = midiDeviceOutService;
        this.audioService = audioService;
        this.designerService = designerService;

        this.midiMapping.setParent(settingsService.getSettings().getMidiMapping());
    }

    // Taken from gstreamers gstfluiddec.c -> handle_buffer
    private void processMidiBuffer(ByteBuffer byteBuffer, MidiRouter midiRouter) {
        int event = byteBuffer.get(0);
        int type = event & 0xf0;

        if (type != 0xf0) {
            // Common messages
            int channel = event & 0x0f;
            int command = event & 0xf0;
            int note = byteBuffer.get(1) & 0x7f;

            // TODO Can result in index out of bounds exception
            int velocity = byteBuffer.get(2) & 0x7f;

            MidiSignal midiSignal = new MidiSignal();
            midiSignal.setChannel(channel);
            midiSignal.setCommand(command);
            midiSignal.setNote(note);
            midiSignal.setVelocity(velocity);

            try {
                midiRouter.sendSignal(midiSignal);
            } catch (InvalidMidiDataException e) {
                logger.error("Could not send MIDI signal from MIDI file", e);
            }

            if (settingsService.getSettings().getEnableMonitor()) {
                activityNotificationMidiService.notifyClients(midiSignal, MidiSignal.MidiDirection.IN, MidiSignal.MidiSource.MIDI_FILE, null);
            }
        }
    }

    private int getAudioBusStartChannel(AudioBus audioBus) {
        int startChannelIndex = 0;

        // Get the starting channel of the current bus
        for (AudioBus settingsAudioBus : settingsService.getSettings().getAudioBusList()) {
            if (settingsAudioBus.getName().equals(audioBus.getName())) {
                break;
            } else {
                startChannelIndex += settingsAudioBus.getChannels();
            }
        }

        return startChannelIndex;
    }

    private void createGstreamerPipeline(boolean hasAudioFile) {
        pipeline = new Pipeline();
        Bus bus = GstApi.GST_API.gst_element_get_bus(pipeline);
        Element audioMixer = null;

        bus.connect((Bus.ERROR) (GstObject source, int code, String message) -> {
            logger.error("GST error: " + message);
            try {
                notificationService.notifyClients(message + " Please check your audio settings.");
            } catch (Exception e) {
                logger.error("Could not notify clients about an error", e);
            }
            try {
                stop();
            } catch (Exception e) {
                logger.error("Could not stop compostion triggered by an error", e);
            }
        });
        bus.connect((Bus.WARNING) (GstObject source, int code, String message) -> logger.warn("GST: " + message));
        bus.connect((Bus.INFO) (GstObject source, int code, String message) -> logger.warn("GST: " + message));
        bus.connect((GstObject source, State old, State newState, State pending) -> {
            if (source.getTypeName().equals("GstPipeline")) {
                if (newState == State.PLAYING) {
                    // We changed to playing, maybe we need to seek to the start position (not possible before playing)
                    if (startPosition > 0) {
                        try {
                            seek(startPosition);
                        } catch (Exception e) {
                            logger.error("Could not set start position when changed to playing", e);
                        }
                        startPosition = 0;
                    }

                    playState = PlayState.PLAYING;

                    if (!isDefaultComposition && !isSample) {
                        try {
                            notificationService.notifyClients(playerService, setService);
                        } catch (Exception e) {
                            logger.error("Could not notify clients about a playing event", e);
                        }
                    }
                }
            }
        });
        bus.connect((Bus.EOS) source -> {
            if (composition.isLoop()) {
                pipeline.seek(0, TimeUnit.MILLISECONDS);
            } else {
                try {
                    playerService.compositionPlayerFinishedPlaying(this);
                } catch (Exception e) {
                    logger.error("Could not stop the composition after end of stream", e);
                }
            }
        });
        bus.connect((Bus bus1, Message message) -> {
            if (message.getType().equals(MessageType.ELEMENT)) {
                Structure structure = message.getStructure();

                if (structure.getName().equals("level")) {
                    try {
                        // We got a level message
                        activityNotificationAudioService.notifyClients(structure.getDoubles("peak"));
                    } catch (Exception e) {
                        logger.error("Could not process level message", e);
                    }
                }
            }
        });

        GstApi.GST_API.gst_object_unref(bus);

        // Create an pipeline, if at least one audiosource is present
        if (hasAudioFile) {
            audioMixer = ElementFactory.make("audiomixer", "audiomixer");
            pipeline.add(audioMixer);

            // Add a capsfilter to enforce multi-channel out. Otherwise only 2 will be mixed
            Element capsFilter = ElementFactory.make("capsfilter", "capsfilter");
            Caps caps = GstApi.GST_API.gst_caps_from_string("audio/x-raw,channels=" + settingsService.getTotalAudioChannels());
            capsFilter.set("caps", caps);
            pipeline.add(capsFilter);

            audioMixer.link(capsFilter);

            Element queue = ElementFactory.make("queue", "audiosinkqueue");
            pipeline.add(queue);

            BaseSink sink = audioService.getGstAudioSink();
            pipeline.add(sink);

            Element level = null;
            if (!isSample && settingsService.getSettings().getEnableMonitor()) {
                level = ElementFactory.make("level", "level");
                // 1000 Milliseconds
                level.set("interval", 1000 * 1000000);
                level.set("post-messages", true);
                pipeline.add(level);
            }

            if (level == null) {
                capsFilter.link(queue);
            } else {
                capsFilter.link(level);
                level.link(queue);
            }

            queue.link(sink);
        }

        // Load all files, create the pipeline and handle exceptions to pipeline-playing
        for (int i = 0; i < composition.getCompositionFileList().size(); i++) {
            CompositionFile compositionFile = composition.getCompositionFileList().get(i);

            if (compositionFile.isActive()) {
                if (compositionFile instanceof MidiCompositionFile) {
                    MidiCompositionFile midiCompositionFile = (MidiCompositionFile) compositionFile;
                    MidiRouter midiRouter = new MidiRouter(settingsService, midi2LightingConvertService, lightingService, midiDeviceOutService, activityNotificationMidiService, midiCompositionFile.getMidiRoutingList());

                    midiRouterList.add(midiRouter);

                    for (MidiRouting midiRouting : midiCompositionFile.getMidiRoutingList()) {
                        midiRouting.getMidiMapping().setParent(midiMapping);
                    }

                    Element midiFileSource = ElementFactory.make("filesrc", "midifilesrc" + i);
                    midiFileSource.set("location", settingsService.getSettings().getBasePath() + settingsService.getSettings().getMediaPath() + File.separator + settingsService.getSettings().getMidiPath() + "/" + compositionFile.getName());
                    pipeline.add(midiFileSource);

                    Element midiParse = ElementFactory.make("midiparse", "midiparse" + i);
                    pipeline.add(midiParse);

                    Element queue = ElementFactory.make("queue", "midisinkqueue" + i);
                    pipeline.add(queue);

                    AppSink midiSink = (AppSink) ElementFactory.make("appsink", "midisink" + i);
                    // Required to actually send the signals
                    midiSink.set("emit-signals", true);
                    pipeline.add(midiSink);

                    midiParse.getSrcPads().get(0).set("offset", (settingsService.getSettings().getOffsetMillisMidi() + compositionFile.getOffsetMillis()) * 1000000L);

                    // Sometimes preroll and sometimes new-sample events get fired. We have
                    // to process both.
                    midiSink.connect((AppSink.NEW_SAMPLE) element -> {
                        Sample sample = element.pullSample();
                        Buffer buffer = sample.getBuffer();
                        processMidiBuffer(buffer.map(false), midiRouter);
                        buffer.unmap();
                        sample.dispose();
                        return FlowReturn.OK;
                    });
                    midiSink.connect((AppSink.NEW_PREROLL) element -> {
                        Sample sample = element.pullPreroll();
                        Buffer buffer = sample.getBuffer();
                        processMidiBuffer(buffer.map(false), midiRouter);
                        buffer.unmap();
                        sample.dispose();
                        return FlowReturn.OK;
                    });

                    midiFileSource.link(midiParse);
                    midiParse.link(queue);
                    queue.link(midiSink);
                } else if (compositionFile instanceof AudioCompositionFile && capabilitiesService.getCapabilities().isGstreamer()) {
                    logger.debug("Add audio file to pipeline");

                    AudioCompositionFile audioCompositionFile = (AudioCompositionFile) compositionFile;

                    URIDecodeBin audioSource = (URIDecodeBin) ElementFactory.make("uridecodebin", "audiouridecodebin" + i);
                    audioSource.set("uri", "file://" + settingsService.getSettings().getBasePath() + settingsService.getSettings().getMediaPath() + File.separator + settingsService.getSettings().getAudioPath() + File.separator + compositionFile.getName());
                    pipeline.add(audioSource);

                    Element audioConvert = ElementFactory.make("audioconvert", "audioconvert" + i);
                    audioSource.connect((Element.PAD_ADDED) (Element element, Pad pad) -> {
                        Caps caps = pad.getCaps();

                        String name = caps.getStructure(0).getName();

                        if ("audio/x-raw-float".equals(name) || "audio/x-raw-int".equals(name) || "audio/x-raw".equals(name)) {
                            pad.link(audioConvert.getSinkPads().get(0));
                        }
                    });

                    audioConvert.getSrcPads().get(0).set("offset", (settingsService.getSettings().getOffsetMillisAudio() + compositionFile.getOffsetMillis()) * 1000000L);
                    pipeline.add(audioConvert);

                    Element audioResample = ElementFactory.make("audioresample", "audioresample" + i);
                    pipeline.add(audioResample);

                    // Apply the mix matrix
                    GValueAPI.GValue mixMatrix = new GValueAPI.GValue();
                    GValueAPI.GVALUE_API.g_value_init(mixMatrix, GstApi.GST_API.gst_value_array_get_type());

                    AudioBus audioBus = settingsService.getAudioBusFromName(audioCompositionFile.getOutputBus());
                    int startChannelIndex = getAudioBusStartChannel(audioBus);

                    // Repeat for each output channel
                    for (int outputChannelIndex = 0; outputChannelIndex < settingsService.getTotalAudioChannels(); outputChannelIndex++) {
                        GValueAPI.GValue outputChannel = new GValueAPI.GValue();
                        GValueAPI.GVALUE_API.g_value_init(outputChannel, GstApi.GST_API.gst_value_array_get_type());

                        // Fill the channel with the input channels
                        for (int inputChannelIndex = 0; inputChannelIndex < audioCompositionFile.getChannels(); inputChannelIndex++) {
                            GValueAPI.GValue matrixCoefficient = new GValueAPI.GValue(GType.FLOAT);

                            // Calculate and set input channel volume 
                            float channelVolume = 0;

                            if (inputChannelIndex < audioBus.getChannels()) {
                                if (outputChannelIndex == startChannelIndex + inputChannelIndex) {
                                    channelVolume = Math.max(audioCompositionFile.getAudioVolume() * settingsService.getSettings().getAudioVolume(), 1.0f);
                                }
                            }
                            matrixCoefficient.setValue(channelVolume);
                            
                            GstApi.GST_API.gst_value_array_append_value(outputChannel, matrixCoefficient.getPointer());
                            GValueAPI.GVALUE_API.g_value_unset(matrixCoefficient);
                        }

                        GstApi.GST_API.gst_value_array_append_value(mixMatrix, outputChannel.getPointer());
                        GValueAPI.GVALUE_API.g_value_unset(outputChannel);
                    }

                    logger.debug("Mix-matrix for " + audioCompositionFile.getName() + ": " + mixMatrix.toString());

                    GstApi.GST_API.g_object_set_property(audioConvert, "mix-matrix", mixMatrix.getPointer());
                    GValueAPI.GVALUE_API.g_value_unset(mixMatrix);

                    // Link the converter/resampler to the mixer
                    audioConvert.link(audioResample);
                    audioResample.link(audioMixer);
                } else if (compositionFile instanceof VideoCompositionFile && capabilitiesService.getCapabilities().isGstreamer()) {
                    logger.debug("Add video file to pipeline");

                    // Does not work on OS X
                    // See http://gstreamer-devel.966125.n4.nabble.com/OpenGL-renderer-window-td4686092.html

                    // TODO Add offset to a video file

                    PlayBin playBin = (PlayBin) ElementFactory.make("playbin", "playbin" + i);
                    playBin.set("uri", "file://" + settingsService.getSettings().getBasePath() + settingsService.getSettings().getMediaPath() + File.separator + settingsService.getSettings().getVideoPath() + File.separator + compositionFile.getName());
                    pipeline.add(playBin);
                }
            }
        }
    }

    // Load all files and construct the complete GST pipeline
    public void loadFiles() throws Exception {
        boolean hasActiveFile = false;
        boolean hasAudioFile = false;
        Project project;

        if (playState != PlayState.STOPPED) {
            return;
        }

        project = designerService.getProjectByCompositionName(composition.getName());

        // Search for active files
        for (CompositionFile compositionFile : composition.getCompositionFileList()) {
            if (compositionFile.isActive()) {
                hasActiveFile = true;
                break;
            }
        }

        if (!hasActiveFile && project == null) {
            // No files to be played and no designer project (maybe a lead sheet)
            if (!isDefaultComposition && !isSample) {
                notificationService.notifyClients(playerService, setService);
            }

            return;
        }

        // Search for audio files
        for (int i = 0; i < composition.getCompositionFileList().size(); i++) {
            CompositionFile compositionFile = composition.getCompositionFileList().get(i);

            if (compositionFile.isActive() && compositionFile instanceof AudioCompositionFile) {
                hasAudioFile = true;
                break;
            }
        }

        if (hasActiveFile && !capabilitiesService.getCapabilities().isGstreamer()) {
            throw new Exception("Gstreamer is required to play this composition but not available");
        }

        playState = PlayState.LOADING;

        if (!isDefaultComposition && !isSample) {
            notificationService.notifyClients(playerService, setService);
        }

        logger.debug(
                "Loading composition '" + composition.getName() + "...");

        // Destroy an old pipeline, if required
        if (pipeline != null) {
            pipeline.stop();
            pipeline.dispose();
            pipeline = null;
        }

        // Destroy an old designer project, if required
        if (this.project != null) {
            this.designerService.close();
        }

        if (hasActiveFile) {
            createGstreamerPipeline(hasAudioFile);
        }

        if (project != null) {
            designerService.load(this, project, pipeline);
            this.project = project;
        }

        logger.debug("Composition '" + composition.getName() + "' loaded");

        // Maybe we are stopping meanwhile
        if (playState == PlayState.LOADING && !isDefaultComposition && !isSample) {
            playState = PlayState.LOADED;
            notificationService.notifyClients(playerService, setService);
        }
    }

    public void play() throws Exception {
        if (composition == null) {
            return;
        }

        // Load the files, if not already done by a previously by a separate call
        loadFiles();

        // All files are loaded -> play the composition (start each file)
        logger.info("Playing composition '" + composition.getName() + "'...");

        if (pipeline != null) {
            pipeline.play();
        }

        if (project != null) {
            designerService.play();
        }
    }

    public void pause() throws Exception {
        if (playState == PlayState.PAUSED) {
            return;
        }

        logger.info("Pausing composition '" + composition.getName() + "'");

        // Pause the composition
        if (pipeline != null) {
            pipeline.pause();
        }

        if (project != null) {
            designerService.pause();
        }

        playState = PlayState.PAUSED;

        if (!isDefaultComposition && !isSample) {
            notificationService.notifyClients(playerService, setService);
        }
    }

    public void stop() throws Exception {
        startPosition = 0;

        if (composition == null || playState == PlayState.STOPPED) {
            return;
        }

        playState = PlayState.STOPPING;

        if (!isDefaultComposition && !isSample) {
            notificationService.notifyClients(playerService, setService);
        }
        logger.info("Stopping composition '" + composition.getName() + "'");

        // Stop the composition
        if (pipeline != null) {
            pipeline.stop();
            pipeline.dispose();
            pipeline = null;
        }

        if (project != null) {
            designerService.close();
        }

        // Close all MIDI routers
        for (MidiRouter midiRouter : midiRouterList) {
            midiRouter.close();
        }

        // Now would be a good moment to run the GC
        if (!isSample) {
            System.gc();
        }

        playState = PlayState.STOPPED;

        if (!isSample && !isDefaultComposition) {
            notificationService.notifyClients(playerService, setService);
        }

        logger.info("Composition '" + composition.getName() + "' stopped");
    }

    public void togglePlay() throws Exception {
        if (playState == PlayState.PLAYING) {
            stop();
        } else {
            play();
        }
    }

    public void seek(long positionMillis) throws Exception {
        // When we seek before pressing play
        startPosition = positionMillis;

        logger.debug("Seek to position " + positionMillis);

        if (pipeline != null) {
            pipeline.seek(positionMillis, TimeUnit.MILLISECONDS);
        }

        if (project != null) {
            designerService.seek(positionMillis);
        }

        if (!isSample) {
            notificationService.notifyClients(playerService, setService);
        }
    }

    public long getPositionMillis() {
        if (startPosition > 0) {
            return startPosition;
        }

        if (composition == null) {
            return 0;
        }

        if (pipeline != null) {
            return pipeline.queryPosition(TimeUnit.MILLISECONDS);
        }

        if (project != null) {
            designerService.getPositionMillis();
        }

        return 0;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof CompositionPlayer) {
            CompositionPlayer compositionPlayer = (CompositionPlayer) object;

            return this.uuid.equals(compositionPlayer.uuid);
        }

        return false;
    }

    public PlayState getPlayState() {
        return playState;
    }

    public void setPlayState(PlayState playState) {
        this.playState = playState;
    }

    public Composition getComposition() {
        return composition;
    }

    public void setComposition(Composition composition) throws Exception {
        this.composition = composition;

        if (!isSample && !isDefaultComposition) {
            notificationService.notifyClients(playerService, setService);
        }
    }

    public boolean isDefaultComposition() {
        return isDefaultComposition;
    }

    public void setDefaultComposition(boolean defaultComposition) {
        this.isDefaultComposition = defaultComposition;
    }

    public boolean isSample() {
        return isSample;
    }

    public void setSample(boolean sample) {
        isSample = sample;
    }

}