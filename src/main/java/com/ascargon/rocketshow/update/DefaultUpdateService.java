package com.ascargon.rocketshow.update;

import com.ascargon.rocketshow.health.HealthService;
import com.ascargon.rocketshow.health.HealthStatus;
import com.ascargon.rocketshow.health.HealthStatusSeverity;
import com.ascargon.rocketshow.settings.SettingsService;
import com.ascargon.rocketshow.util.RebootService;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class DefaultUpdateService implements UpdateService {

    private final static Logger logger = LoggerFactory.getLogger(DefaultUpdateService.class);

    private final RebootService rebootService;
    private final RaucService raucService;
    private final HealthService healthService;
    private final VersionService versionService;
    private final UpdateNotificationService updateNotificationService;
    private final SettingsService settingsService;

    private final String FILE_NAME = "update";

    private UpdateState updateState;

    public DefaultUpdateService(
            RebootService rebootService,
            RaucService raucService,
            HealthService healthService,
            VersionService versionService,
            UpdateNotificationService updateNotificationService,
            SettingsService settingsService
    ) {
        this.rebootService = rebootService;
        this.raucService = raucService;
        this.healthService = healthService;
        this.versionService = versionService;
        this.updateNotificationService = updateNotificationService;
        this.settingsService = settingsService;

        // Check, whether we can finish an ongoing update after reboot
        updateState = loadFromFile();
        if (updateState == null) {
            updateState = new UpdateState();
        } else {
            if (UpdateStep.REBOOTING.equals(updateState.getStep())) {
                if (isOriginalSlot()) {
                    // We booted back into the original slot -> watchdog failed and falled back
                    // to the original slot
                    error("Fell back to original slot due to Watchdog failure");
                } else {
                    // We booted into the updated slot for the first time
                    try {
                        finishUpdate();
                    } catch (Exception e) {
                        error("Could not finish update: " + e);
                    }
                }
            } else if (UpdateStep.FALLING_BACK.equals(updateState.getStep())) {
                // We booted back into the original slot after the update failed
                error("Update failed. Reverted original state.");
            } else if (UpdateStep.UPDATING.equals(updateState.getStep())) {
                // We got interrupted (e.g. by a reboot or crash) while still installing.
                // The installation runs synchronously in the update process and cannot be
                // resumed, so mark the update as failed instead of waiting forever.
                error("Update was interrupted during installation.");
            }
        }
    }

    // Whether we run in the slot the update was started from (e.g. the new slot did not come up and the device
    // fell back). Updates started by older versions did not store the original slot, so the running version is
    // compared as well.
    private boolean isOriginalSlot() {
        String currentSlot = raucService.getCurrentSlot();
        if (currentSlot != null && currentSlot.equals(updateState.getOriginalRaucSlot())) {
            return true;
        }

        try {
            String currentVersion = versionService.getCurrentVersionInfo().getVersion();
            return currentVersion != null && currentVersion.equals(updateState.getUpdatingFromVersion());
        } catch (Exception e) {
            logger.error("Could not read the current version", e);
            return false;
        }
    }

    private UpdateState loadFromFile() {
        File file = new File(settingsService.getSettings().getBasePath() + File.separator + FILE_NAME + ".xml");
        UpdateState updateState = null;

        if (file.exists()) {
            // We have an update state -> restore it from the file
            try {
                JAXBContext jaxbContext = JAXBContext.newInstance(UpdateState.class);

                Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
                updateState = (UpdateState) jaxbUnmarshaller.unmarshal(file);
            } catch (JAXBException e) {
                logger.error("Could not the update state from file", e);
            }
        }

        return updateState;
    }

    public void saveToFile(UpdateState updateState) {
        try {
            String directory = settingsService.getSettings().getBasePath();

            File file = new File(directory + File.separator + FILE_NAME + ".xml");
            JAXBContext jaxbContext = JAXBContext.newInstance(UpdateState.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

            // output pretty printed
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            jaxbMarshaller.marshal(updateState, file);
        } catch (JAXBException e) {
            logger.error("Could not save the update state", e);
        }
    }

    private void sendAndSave(UpdateState updateState) throws Exception {
        saveToFile(updateState);
        updateNotificationService.notifyClients(updateState);
    }

    private void error(String error) {
        logger.error(error);

        updateState.setStep(UpdateStep.FINISHED);
        updateState.setError(error);

        try {
            sendAndSave(updateState);
        } catch (Exception e) {
            logger.error("Could not log update error", e);
        }
    }

    private void finishUpdate() throws Exception {
        // We booted into the updated slot for the first time.
        // Check, whether we are healthy and could upgrade the version or whether
        // we need to fall back

        HealthStatus healthStatus = healthService.getHealthStatus();

        if (healthStatus.getHealthStatusSeverity() == HealthStatusSeverity.OK ||
                healthStatus.getHealthStatusSeverity() == HealthStatusSeverity.DEGRADED) {

            // Update successful
            raucService.markGood();

            updateState.setStep(UpdateStep.FINISHED);
            sendAndSave(updateState);
        } else {
            // Update failed -> reboot and fallback to the old slot
            logger.error("Update failed. Health check failed: {}", healthStatus.toFailureString());

            updateState.setStep(UpdateStep.FALLING_BACK);
            sendAndSave(updateState);
            rebootService.reboot();
        }
    }

    @Override
    public void update(boolean testBranch) throws Exception {
        logger.info("Updating system...");
        logger.info("Test branch: {}", testBranch);

        VersionInfo remoteVersionInfo = versionService.getRemoteVersionInfo(testBranch);
        String currentVersion = versionService.getCurrentVersionInfo().getVersion();
        String newVersion = remoteVersionInfo.getVersion();
        String currentSlot = raucService.getCurrentSlot();

        logger.info("Current version: {}", currentVersion);
        logger.info("New version: {}", newVersion);
        logger.info("Current RAUC slot: {}", currentSlot);

        if (newVersion.equals(currentVersion)) {
            error("New version is equal to the currently installed version");
            return;
        }

        updateState.setStep(UpdateStep.UPDATING);
        updateState.setError(null);
        updateState.setUpdatingFromVersion(currentVersion);
        updateState.setOriginalRaucSlot(currentSlot);
        updateState.setProgressPercentage(0);
        sendAndSave(updateState);

        try {
            raucService.installBundle(remoteVersionInfo.getRaucBundle());
        } catch (Exception e) {
            error("Unable to install RAUC bundle: " + e.getMessage());
            return;
        }

        updateState.setStep(UpdateStep.REBOOTING);
        sendAndSave(updateState);

        rebootService.tryboot();
    }

    @Override
    public UpdateState getCurrentState() {
        return updateState;
    }

}