package com.ascargon.rocketshow;

import com.ascargon.rocketshow.audio.AudioBus;
import com.ascargon.rocketshow.midi.MidiDevice;
import com.ascargon.rocketshow.midi.MidiMapping;
import com.ascargon.rocketshow.midi.MidiUtil;
import com.ascargon.rocketshow.util.ShellManager;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.sound.midi.MidiUnavailableException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class FileSettingsService {

    private final static Logger logger = Logger.getLogger(Settings.class);

    private Settings settings;

    // Create an own logging enum to save it in the settings xml
    public enum LoggingLevel {
        ERROR, WARN, INFO, DEBUG, TRACE
    }

    // Possible audio outputs
    public enum AudioOutput {
        HEADPHONES, HDMI, DEVICE
    }

    private void initDefaultSettings() {
        // Initialize default settings

        settings.setBasePath("/opt/rocketshow/");

        settings.setMidiInDevice(new MidiDevice());
        settings.setMidiOutDevice(new MidiDevice());

        // Global MIDI mapping
        settings.setMidiMapping(new MidiMapping());

        try {
            List<MidiDevice> midiInDeviceList;
            midiInDeviceList = MidiUtil.getMidiDevices(MidiUtil.MidiDirection.IN);
            if (midiInDeviceList.size() > 0) {
                settings.setMidiInDevice(midiInDeviceList.get(0));
            }
        } catch (MidiUnavailableException e) {
            logger.error("Could not get any MIDI IN devices");
            logger.error(e.getStackTrace());
        }

        try {
            List<MidiDevice> midiOutDeviceList;
            midiOutDeviceList = MidiUtil.getMidiDevices(MidiUtil.MidiDirection.OUT);
            if (midiOutDeviceList.size() > 0) {
                settings.setMidiOutDevice(midiOutDeviceList.get(0));
            }
        } catch (MidiUnavailableException e) {
            logger.error("Could not get any MIDI OUT devices");
            logger.error(e.getStackTrace());
        }

        settings.setDmxSendDelayMillis(10);

        settings.setOffsetMillisAudio(0);
        settings.setOffsetMillisMidi(0);
        settings.setOffsetMillisVideo(0);

        settings.setAudioOutput(AudioOutput.HEADPHONES);
        settings.setAudioRate(44100 /* or 48000 */);

        settings.setLoggingLevel(LoggingLevel.INFO);

        settings.setEnableRaspberryGpio(true);

        updateSystem();
    }

    public RemoteDevice getRemoteDeviceByName(String name) {
        for (RemoteDevice remoteDevice : settings.getRemoteDeviceList()) {
            if (remoteDevice.getName().equals(name)) {
                return remoteDevice;
            }
        }

        return null;
    }

    private void setSystemAudioOutput(int id) throws Exception {
        ShellManager shellManager = new ShellManager(new String[]{"amixer", "cset", "numid=3", String.valueOf(id)});
        shellManager.getProcess().waitFor();
    }

    private int getTotalAudioChannels() {
        int total = 0;

        for (AudioBus audioBus : settings.getAudioBusList()) {
            total += audioBus.getChannels();
        }

        return total;
    }

    private String getBusNameFromId(int id) {
        return "bus" + (id + 1);
    }

    public String getAlsaDeviceFromOutputBus(String outputBus) {
        logger.debug("Find ALSA device for bus name '" + outputBus + "'...");

        // Get an alsa device name from a bus name
        for (int i = 0; i < settings.getAudioBusList().size(); i++) {
            AudioBus audioBus = settings.getAudioBusList().get(i);

            logger.debug("Got bus '" + audioBus.getName() + "'");

            if (outputBus != null && outputBus.equals(audioBus.getName())) {
                logger.debug("Found device '" + getBusNameFromId(i) + "'");

                return getBusNameFromId(i);
            }
        }

        // Return a default bus, if none is found
        if (settings.getAudioBusList().size() > 0) {
            return getBusNameFromId(0);
        }

        return "";
    }

    private String getAlsaSettings() {
        // Generate the ALSA settings
        StringBuilder alsaSettings = new StringBuilder();
        int currentChannel = 0;

        if (settings.getAudioDevice() == null) {
            // We got no audio device
            return "";
        }

        // Build the general device settings
        alsaSettings.append("pcm.dshare {\n" + "  type dmix\n" + "  ipc_key 2048\n" + "  slave {\n" + "    pcm \"hw:").append(settings.getAudioDevice().getKey()).append("\"\n").append("    rate ").append(settings.getAudioRate()).append("\n").append("    channels ").append(getTotalAudioChannels()).append("\n").append("  }\n").append("  bindings {\n");

        // Add all channels
        for (int i = 0; i < getTotalAudioChannels(); i++) {
            alsaSettings.append("    ").append(i).append(" ").append(i).append("\n");
        }

        alsaSettings.append("  }\n" + "}\n");

        // List each bus
        for (int i = 0; i < settings.getAudioBusList().size(); i++) {
            AudioBus audioBus = settings.getAudioBusList().get(i);

            alsaSettings.append("\n" + "pcm.").append(getBusNameFromId(i)).append(" {\n").append("  type plug\n").append("  slave {\n").append("    pcm \"dshare\"\n").append("    channels ").append(getTotalAudioChannels()).append("\n").append("  }\n");

            // Add each channel to the bus
            for (int j = 0; j < audioBus.getChannels(); j++) {
                alsaSettings.append("  ttable.").append(j).append(".").append(currentChannel).append(" 1\n");

                currentChannel++;
            }

            alsaSettings.append("}\n");
        }

        return alsaSettings.toString();
    }

    private void updateAudioSystem() throws Exception {
        if (settings.getAudioOutput() == AudioOutput.HEADPHONES) {
            setSystemAudioOutput(1);
        } else if (settings.getAudioOutput() == AudioOutput.HDMI) {
            setSystemAudioOutput(2);
        } else if (settings.getAudioOutput() == AudioOutput.DEVICE) {
            // Write the audio settings to /home/.asoundrc and use ALSA to
            // output audio on the selected device name
            File alsaSettings = new File("/home/rocketshow/.asoundrc");

            try {
                FileWriter fileWriter = new FileWriter(alsaSettings, false);
                fileWriter.write(getAlsaSettings());
                fileWriter.close();
            } catch (IOException e) {
                logger.error("Could not write .asoundrc", e);
            }
        }
    }

    private void updateLoggingLevel() {
        // Set the proper logging level (map from the log4j enum to our own
        // enum)
        switch (settings.getLoggingLevel()) {
            case INFO:
                LogManager.getRootLogger().setLevel(Level.INFO);
                break;
            case WARN:
                LogManager.getRootLogger().setLevel(Level.WARN);
                break;
            case ERROR:
                LogManager.getRootLogger().setLevel(Level.ERROR);
                break;
            case DEBUG:
                LogManager.getRootLogger().setLevel(Level.DEBUG);
                break;
            case TRACE:
                LogManager.getRootLogger().setLevel(Level.TRACE);
                break;
        }
    }

    private void updateWlanAp() {
        String apConfig = "";
        String statusCommand;

        // Update the access point configuration
        apConfig += "interface=wlan0\n";
        apConfig += "driver=nl80211\n";
        apConfig += "ssid=" + settings.getWlanApSsid() + "\n";
        apConfig += "utf8_ssid=1\n";
        apConfig += "hw_mode=g\n";
        apConfig += "channel=7\n";
        apConfig += "wmm_enabled=0\n";
        apConfig += "macaddr_acl=0\n";
        apConfig += "auth_algs=1\n";

        if (settings.isWlanApSsidHide()) {
            apConfig += "ignore_broadcast_ssid=1\n";
        } else {
            apConfig += "ignore_broadcast_ssid=0\n";
        }

        if (settings.getWlanApPassphrase() != null && settings.getWlanApPassphrase().length() >= 8) {
            apConfig += "wpa=2\n";
            apConfig += "wpa_passphrase=" + settings.getWlanApPassphrase() + "\n";
        }

        apConfig += "wpa_key_mgmt=WPA-PSK\n";
        apConfig += "wpa_pairwise=TKIP\n";
        apConfig += "rsn_pairwise=CCMP\n";

        try {
            FileWriter fileWriter = new FileWriter("/etc/hostapd/hostapd.conf", false);
            fileWriter.write(apConfig);
            fileWriter.close();
        } catch (IOException e) {
            logger.error("Could not write /etc/hostapd/hostapd.conf", e);
        }

        // Activate/deactivate the access point completely
        if (settings.isWlanApEnable()) {
            statusCommand = "enable";
        } else {
            statusCommand = "disable";
        }

        try {
            new ShellManager(new String[]{"sudo", "systemctl", statusCommand, "hostapd"});
        } catch (IOException e) {
            logger.error("Could not update the access point status with '" + statusCommand + "'", e);
        }
    }

    private void updateSystem() {
        // Update all system settings

        try {
            updateAudioSystem();
        } catch (Exception e) {
            logger.error("Could not update the audio system settings", e);
        }

        try {
            updateLoggingLevel();
        } catch (Exception e) {
            logger.error("Could not update the logging level system settings", e);
        }

        try {
            updateWlanAp();
        } catch (Exception e) {
            logger.error("Could not update the wireless access point settings", e);
        }
    }

}
