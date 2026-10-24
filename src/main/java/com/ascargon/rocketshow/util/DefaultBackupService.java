package com.ascargon.rocketshow.util;

import com.ascargon.rocketshow.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Arrays;
import java.util.List;

/**
 * Manages backups.
 *
 * @author Moritz A. Vieli
 */
@Service
public class DefaultBackupService implements BackupService {

    private final static Logger logger = LoggerFactory.getLogger(DefaultBackupService.class);

    private final List<String> backedUpFileList = Arrays.asList("compositions", "sets", "designer", "fixtures", "media", "session.xml", "settings.xml");

    private final SettingsService settingsService;
    private final DiskSpaceService diskSpaceService;
    private final ChunkedFileUploadService chunkedFileUploadService;
    private final RebootService rebootService;

    private final static String BACKUP_FILE_NAME = "backup.tar.gz";

    private final File backupFile;

    public DefaultBackupService(SettingsService settingsService, DiskSpaceService diskSpaceService, ChunkedFileUploadService chunkedFileUploadService, RebootService rebootService) {
        this.settingsService = settingsService;
        this.diskSpaceService = diskSpaceService;
        this.chunkedFileUploadService = chunkedFileUploadService;
        this.rebootService = rebootService;

        backupFile = new File(settingsService.getSettings().getBasePath() + BACKUP_FILE_NAME);
    }

    private void deleteBackupFile() throws Exception {
        // delete an eventually already created backup file
        if (backupFile.exists()) {
            boolean result = backupFile.delete();
            if (!result) {
                throw new Exception("Could not delete backup file '" + backupFile.getPath() + "'");
            }
        }
    }

    @Override
    public File create() throws Exception {
        File backupFile = new File(settingsService.getSettings().getBasePath() + BACKUP_FILE_NAME);

        deleteBackupFile();

        // check free disk space at least 50 % (because we use it for the backup file temporarily)
        DiskSpace diskSpace = diskSpaceService.get();

        if (diskSpace.getUsedMB() > 0) {
            // does not work on the mac e.g.
            long usedDiskSpacePercentage = Math.round(100 * diskSpace.getUsedMB() / (diskSpace.getUsedMB() + diskSpace.getAvailableMB()));
            if (usedDiskSpacePercentage > 50) {
                throw new Exception("Not enough free disk space available on the device to create the backup (at least 50% required).");
            }
        }

        // tar all backed up files
        // don't use zip, because the file permissions (e.g. execution rights) are not preserved easily with java
        ShellManager shellManager = new ShellManager(new String[]{"bash", "-c", "cd " + settingsService.getSettings().getBasePath() + " && tar -czpf " + BACKUP_FILE_NAME + " " + String.join(" ", backedUpFileList)});
        shellManager.getProcess().waitFor();
        shellManager.close();

        // Return the prepared zip
        return backupFile;
    }

    @Override
    public void restoreInit() throws Exception {
        deleteBackupFile();
    }

    @Override
    public void restoreAddChunk(InputStream inputStream) throws Exception {
        chunkedFileUploadService.handleChunk(inputStream, backupFile);
    }

    // Make sure the uploaded backup can be unpacked before anything gets deleted. A broken backup (e.g. an
    // incomplete upload) would otherwise delete all data and restore nothing.
    private void checkBackupFile() throws Exception {
        ShellManager shellManager = new ShellManager(new String[]{"tar", "-tzf", backupFile.getPath()});

        // Read the listed files, so tar can't block on a full output buffer
        shellManager.getProcess().getInputStream().transferTo(OutputStream.nullOutputStream());
        int exitCode = shellManager.getProcess().waitFor();
        shellManager.close();

        if (exitCode != 0) {
            deleteBackupFile();
            throw new Exception("The backup file is broken and could not be restored");
        }
    }

    @Override
    public void restoreFinish() throws Exception {
        // automatic restore only works linux-based environments
        logger.info("Start restoring backup...");

        checkBackupFile();

        String basePath = settingsService.getSettings().getBasePath();

        // run async
        Runnable task = () -> {
            ShellManager shellManager;

            try {
                // delete all backed up files/directories in the basepath
                List<String> fullPaths = backedUpFileList.stream()
                        .map(file -> basePath + File.separator + file)
                        .toList();
                shellManager = new ShellManager(new String[]{"bash", "-c", "rm -rf " + String.join(" ", fullPaths)});
                shellManager.getProcess().waitFor();
                shellManager.close();

                // unpack the backup-file
                shellManager = new ShellManager(new String[]{"tar", "-xzpf", settingsService.getSettings().getBasePath() + BACKUP_FILE_NAME, "-C", basePath});
                shellManager.getProcess().waitFor();
                shellManager.close();

                // delete the backup file
                shellManager = new ShellManager(new String[]{"rm", "-rf", settingsService.getSettings().getBasePath() + BACKUP_FILE_NAME});
                shellManager.getProcess().waitFor();
                shellManager.close();

                logger.info("Backup has been restored. Reboot...");

                rebootService.reboot();
            } catch (Exception e) {
                logger.error("Could not restore backup", e);
            }
        };

        Thread thread = new Thread(task);
        thread.start();
    }

}
