package com.ascargon.rocketshow;

import com.ascargon.rocketshow.composition.Composition;
import com.ascargon.rocketshow.session.Session;
import com.ascargon.rocketshow.settings.Settings;
import com.ascargon.rocketshow.update.UpdateState;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads the files written by earlier releases with the current classes. Add the files of every release to
 * src/test/resources/compat/&lt;version&gt;/ (settings.xml, session.xml, update.xml, compositions/, sets/).
 * <p>
 * An element the current version does not understand fails the test, because its content would be lost silently
 * on the next save.
 */
class CompatibilityTest {

    // Elements of older releases that are dropped on purpose (or not migrated yet), per file type
    private final static Map<Class<?>, Set<String>> IGNORED_ELEMENTS = Map.of(
            Composition.class, Set.of(
                    // Only written. The file type follows from the element (audioFile, midiFile, videoFile).
                    "type",
                    // TODO 2.x compositions assign audio files to a bus by name. The name is not migrated to the bus
                    //  UUID yet, so these files play on the first bus. See testing/README.md, known issues.
                    "outputBus"
            )
    );

    private Class<?> getType(Path relativePath) {
        String fileName = relativePath.getFileName().toString();
        String directory = relativePath.getNameCount() > 2 ? relativePath.getName(1).toString() : "";

        if (directory.equals("compositions")) {
            return Composition.class;
        } else if (directory.equals("sets")) {
            return com.ascargon.rocketshow.composition.Set.class;
        } else if (fileName.equals("settings.xml")) {
            return Settings.class;
        } else if (fileName.equals("session.xml")) {
            return Session.class;
        } else if (fileName.equals("update.xml")) {
            return UpdateState.class;
        }

        return null;
    }

    private boolean isIgnored(String message, Class<?> type) {
        for (String element : IGNORED_ELEMENTS.getOrDefault(type, Set.of())) {
            if (message.contains("local:\"" + element + "\"")) {
                return true;
            }
        }

        return false;
    }

    private void load(Path file, Path relativePath, Class<?> type) throws Exception {
        List<String> problems = new ArrayList<>();

        Unmarshaller unmarshaller = JAXBContext.newInstance(type).createUnmarshaller();
        unmarshaller.setEventHandler(event -> {
            if (!isIgnored(event.getMessage(), type)) {
                problems.add(event.getMessage());
            }

            // Continue to collect all problems of the file
            return true;
        });

        assertNotNull(unmarshaller.unmarshal(file.toFile()));
        assertTrue(problems.isEmpty(), relativePath + " is not understood by the current version: " + problems);
    }

    @TestFactory
    Stream<DynamicTest> loadsTheFilesOfEarlierReleases() throws Exception {
        Path compatPath = Path.of(getClass().getResource("/compat").toURI());

        List<Path> files;
        try (Stream<Path> paths = Files.walk(compatPath)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }

        assertFalse(files.isEmpty());

        return files.stream().map(file -> {
            Path relativePath = compatPath.relativize(file);

            return DynamicTest.dynamicTest(relativePath.toString(), () -> {
                Class<?> type = getType(relativePath);

                if (type == null) {
                    fail(relativePath + " is an unknown file. Add its type to the test.");
                }

                load(file, relativePath, type);
            });
        });
    }

}
