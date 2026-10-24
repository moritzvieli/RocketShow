package com.ascargon.rocketshow.util;

import com.ascargon.rocketshow.settings.Settings;
import com.ascargon.rocketshow.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Backups are created and restored with bash and tar
@EnabledOnOs({OS.LINUX, OS.MAC})
class DefaultBackupServiceTest {

    @TempDir
    Path basePath;

    private final RebootService rebootService = mock(RebootService.class);
    private final CountDownLatch rebooted = new CountDownLatch(1);

    private DefaultBackupService backupService;

    @BeforeEach
    void setUp() throws Exception {
        Settings settings = new Settings();
        settings.setBasePath(basePath + File.separator);

        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);

        // Used disk space is unknown (0) -> no free disk space check
        DiskSpaceService diskSpaceService = mock(DiskSpaceService.class);
        when(diskSpaceService.get()).thenReturn(new DiskSpace());

        doAnswer(invocation -> {
            rebooted.countDown();
            return null;
        }).when(rebootService).reboot();

        backupService = new DefaultBackupService(settingsService, diskSpaceService, new DefaultChunkedFileUploadService(), rebootService);
    }

    private void write(String path, String content) throws Exception {
        Path file = basePath.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String path) throws Exception {
        return Files.readString(basePath.resolve(path));
    }

    private void upload(byte[] backup) throws Exception {
        // Upload in two chunks like the web app does with larger files
        int half = backup.length / 2;

        backupService.restoreInit();
        backupService.restoreAddChunk(new ByteArrayInputStream(Arrays.copyOfRange(backup, 0, half)));
        backupService.restoreAddChunk(new ByteArrayInputStream(Arrays.copyOfRange(backup, half, backup.length)));
    }

    private void writeData() throws Exception {
        write("compositions/Song.xml", "<composition/>");
        write("sets/Set.xml", "<set/>");
        write("media/audio/song.wav", "audio");
        write("session.xml", "<session/>");
        write("settings.xml", "<settings>original</settings>");
    }

    private byte[] createBackup() throws Exception {
        return Files.readAllBytes(backupService.create().toPath());
    }

    @Test
    void restoresTheCreatedBackup() throws Exception {
        writeData();
        byte[] backup = createBackup();

        // Change the data after the backup
        write("settings.xml", "<settings>changed</settings>");
        Files.delete(basePath.resolve("compositions/Song.xml"));
        write("compositions/New.xml", "<composition/>");

        upload(backup);
        backupService.restoreFinish();

        assertTrue(rebooted.await(30, TimeUnit.SECONDS));
        assertEquals("<settings>original</settings>", read("settings.xml"));
        assertEquals("<composition/>", read("compositions/Song.xml"));
        assertFalse(Files.exists(basePath.resolve("compositions/New.xml")));
        assertEquals("audio", read("media/audio/song.wav"));
        assertFalse(Files.exists(basePath.resolve("backup.tar.gz")));
    }

    @Test
    void keepsTheDataWhenTheBackupIsIncomplete() throws Exception {
        writeData();
        byte[] backup = createBackup();

        upload(Arrays.copyOf(backup, backup.length / 2));

        assertThrows(Exception.class, () -> backupService.restoreFinish());
        verify(rebootService, never()).reboot();
        assertEquals("<settings>original</settings>", read("settings.xml"));
        assertEquals("<composition/>", read("compositions/Song.xml"));
    }

    @Test
    void keepsTheDataWhenTheUploadIsNoBackup() throws Exception {
        writeData();

        upload("this is not a backup".getBytes(StandardCharsets.UTF_8));

        assertThrows(Exception.class, () -> backupService.restoreFinish());
        verify(rebootService, never()).reboot();
        assertEquals("<settings>original</settings>", read("settings.xml"));
        assertEquals("<composition/>", read("compositions/Song.xml"));
    }

}
