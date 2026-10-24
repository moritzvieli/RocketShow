package com.ascargon.rocketshow.update;

import com.ascargon.rocketshow.health.HealthService;
import com.ascargon.rocketshow.health.HealthStatus;
import com.ascargon.rocketshow.health.HealthStatusSeverity;
import com.ascargon.rocketshow.settings.Settings;
import com.ascargon.rocketshow.settings.SettingsService;
import com.ascargon.rocketshow.util.RebootService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the update state machine across reboots. Each boot creates a new service, which reads the update state
 * file written by the previous boot, like the app does on a device.
 */
class DefaultUpdateServiceTest {

    private final static String ORIGINAL_SLOT = "rootfs.0";
    private final static String NEW_SLOT = "rootfs.1";
    private final static String ORIGINAL_VERSION = "3.0.0";
    private final static String NEW_VERSION = "3.1.0";
    private final static String BUNDLE_URL = "https://rocketshow.net/update/rocketshow.raucb";

    @TempDir
    Path basePath;

    private final RebootService rebootService = mock(RebootService.class);
    private final RaucService raucService = mock(RaucService.class);
    private final HealthService healthService = mock(HealthService.class);
    private final VersionService versionService = mock(VersionService.class);
    private final UpdateNotificationService updateNotificationService = mock(UpdateNotificationService.class);
    private final SettingsService settingsService = mock(SettingsService.class);

    @BeforeEach
    void setUp() {
        Settings settings = new Settings();
        settings.setBasePath(basePath + File.separator);
        when(settingsService.getSettings()).thenReturn(settings);
    }

    private VersionInfo getVersionInfo(String version) {
        VersionInfo versionInfo = new VersionInfo();
        versionInfo.setVersion(version);
        versionInfo.setRaucBundle(BUNDLE_URL);
        return versionInfo;
    }

    // Start the app on the given slot and version. The constructor finishes an ongoing update.
    private DefaultUpdateService boot(String slot, String version, HealthStatusSeverity healthStatusSeverity) throws Exception {
        HealthStatus healthStatus = new HealthStatus();
        healthStatus.setHealthStatusSeverity(healthStatusSeverity);

        when(raucService.getCurrentSlot()).thenReturn(slot);
        when(versionService.getCurrentVersionInfo()).thenReturn(getVersionInfo(version));
        when(healthService.getHealthStatus()).thenReturn(healthStatus);

        return new DefaultUpdateService(rebootService, raucService, healthService, versionService, updateNotificationService, settingsService);
    }

    // Install the new version on the original slot, until the device tryboots into the new slot
    private DefaultUpdateService updateToNewVersion() throws Exception {
        DefaultUpdateService updateService = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK);
        when(versionService.getRemoteVersionInfo(false)).thenReturn(getVersionInfo(NEW_VERSION));

        updateService.update(false);
        return updateService;
    }

    // Write the update state like an older version did, which did not store the original slot
    private void writeStateOfOlderVersion(UpdateStep updateStep) throws Exception {
        UpdateState updateState = new UpdateState();
        updateState.setStep(updateStep);
        updateState.setUpdatingFromVersion(ORIGINAL_VERSION);

        boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).saveToFile(updateState);
    }

    @Test
    void updateInstallsTheBundleAndTrybootsIntoTheNewSlot() throws Exception {
        UpdateState updateState = updateToNewVersion().getCurrentState();

        verify(raucService).installBundle(BUNDLE_URL);
        verify(rebootService).tryboot();
        assertEquals(UpdateStep.REBOOTING, updateState.getStep());
        assertEquals(ORIGINAL_SLOT, updateState.getOriginalRaucSlot());
        assertEquals(ORIGINAL_VERSION, updateState.getUpdatingFromVersion());
    }

    @Test
    void healthyFirstBootInTheNewSlotMarksTheSlotGood() throws Exception {
        updateToNewVersion();

        UpdateState updateState = boot(NEW_SLOT, NEW_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertNull(updateState.getError());
        verify(raucService).markGood();
    }

    @Test
    void degradedFirstBootInTheNewSlotMarksTheSlotGood() throws Exception {
        updateToNewVersion();

        UpdateState updateState = boot(NEW_SLOT, NEW_VERSION, HealthStatusSeverity.DEGRADED).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertNull(updateState.getError());
        verify(raucService).markGood();
    }

    @Test
    void unhealthyFirstBootInTheNewSlotFallsBack() throws Exception {
        updateToNewVersion();

        UpdateState updateState = boot(NEW_SLOT, NEW_VERSION, HealthStatusSeverity.FAIL_REBOOT_DEVICE).getCurrentState();

        assertEquals(UpdateStep.FALLING_BACK, updateState.getStep());
        verify(rebootService).reboot();
        verify(raucService, never()).markGood();
    }

    @Test
    void bootAfterFallingBackReportsTheFailure() throws Exception {
        updateToNewVersion();
        boot(NEW_SLOT, NEW_VERSION, HealthStatusSeverity.FAIL_REBOOT_DEVICE);

        UpdateState updateState = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertEquals("Update failed. Reverted original state.", updateState.getError());
        verify(raucService, never()).markGood();
    }

    @Test
    void bootBackIntoTheOriginalSlotReportsTheFailure() throws Exception {
        // E.g. the new slot did not boot or the app did not start, and the device fell back to the original slot
        updateToNewVersion();

        UpdateState updateState = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertEquals("Fell back to original slot due to Watchdog failure", updateState.getError());
        verify(raucService, never()).markGood();
    }

    @Test
    void bootWithoutReadableSlotStartsTheApp() throws Exception {
        updateToNewVersion();

        UpdateState updateState = boot(null, NEW_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertNull(updateState.getError());
    }

    @Test
    void updateStartedByOlderVersionFinishesInTheNewSlot() throws Exception {
        writeStateOfOlderVersion(UpdateStep.REBOOTING);

        UpdateState updateState = boot(NEW_SLOT, NEW_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertNull(updateState.getError());
        verify(raucService).markGood();
    }

    @Test
    void updateStartedByOlderVersionReportsTheFallBack() throws Exception {
        writeStateOfOlderVersion(UpdateStep.REBOOTING);

        UpdateState updateState = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertEquals("Fell back to original slot due to Watchdog failure", updateState.getError());
        verify(raucService, never()).markGood();
    }

    @Test
    void bootDuringInstallationReportsTheInterruption() throws Exception {
        writeStateOfOlderVersion(UpdateStep.UPDATING);

        UpdateState updateState = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).getCurrentState();

        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertEquals("Update was interrupted during installation.", updateState.getError());
    }

    @Test
    void failedInstallationReportsTheErrorWithoutReboot() throws Exception {
        doThrow(new Exception("RAUC exited with exit code 1")).when(raucService).installBundle(anyString());

        updateToNewVersion();

        UpdateState updateState = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK).getCurrentState();
        assertEquals(UpdateStep.FINISHED, updateState.getStep());
        assertNotNull(updateState.getError());
        assertTrue(updateState.getError().startsWith("Unable to install RAUC bundle"));
        verify(rebootService, never()).tryboot();
    }

    @Test
    void sameVersionIsNotInstalled() throws Exception {
        DefaultUpdateService updateService = boot(ORIGINAL_SLOT, ORIGINAL_VERSION, HealthStatusSeverity.OK);
        when(versionService.getRemoteVersionInfo(false)).thenReturn(getVersionInfo(ORIGINAL_VERSION));

        updateService.update(false);

        assertEquals("New version is equal to the currently installed version", updateService.getCurrentState().getError());
        verify(raucService, never()).installBundle(anyString());
        verify(rebootService, never()).tryboot();
    }

}
