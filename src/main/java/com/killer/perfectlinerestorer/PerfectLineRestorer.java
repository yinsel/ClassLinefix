package com.killer.perfectlinerestorer;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import com.killer.perfectlinerestorer.processor.ClassProcessor;
import com.killer.perfectlinerestorer.processor.JarProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Locale;

/** Coordinates backups, in-place directory restoration, and single-file output. */
public class PerfectLineRestorer {
    private static final Logger logger = LoggerFactory.getLogger(PerfectLineRestorer.class);
    private final Main.CommandLineConfig config;
    private final LineNumberRestorer restorer;
    private int processedFiles;
    private int skippedFiles;

    public PerfectLineRestorer(Main.CommandLineConfig config) {
        this.config = config;
        this.restorer = new LineNumberRestorer(config);
    }

    public void process() throws IOException {
        Path input = Paths.get(config.getInputDir()).toAbsolutePath().normalize();
        if (!Files.isReadable(input)) {
            throw new IOException("Input is not readable: " + input);
        }
        Path output = Paths.get(config.getOutputDir());
        if (Files.isDirectory(input)) {
            Path backup = Paths.get(config.getBackupDir());
            // Never reuse a previous run's backup or follow an existing output symlink.
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("Backup/output already exists; move it before retrying: "
                        + backup + " / " + output);
            }
            Files.createDirectory(backup);
            Files.createDirectory(output);
            logger.info("Backup directory: {}", backup);
            Files.walkFileTree(input, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // Do not overwrite targets outside the input tree through symbolic links.
                    if (attrs.isRegularFile() && isCandidate(file)) {
                        Path relative = input.relativize(file);
                        processDirectoryFile(file, backup.resolve(relative), output.resolve(relative));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            if (!Files.isRegularFile(input) || !isJavaFile(input)) {
                throw new IOException("Input must be a directory, CLASS or JAR file: " + input);
            }
            processSingleFile(input, output);
        }
        logger.info("Processing completed. Files modified: {}; files skipped: {}", processedFiles, skippedFiles);
    }

    private boolean isJavaFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".class") || name.endsWith(".jar");
    }

    private boolean isJar(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private boolean isCandidate(Path file) {
        return isJavaFile(file) && !(config.isClassOnly() && isJar(file));
    }

    private boolean transform(Path input, Path output) throws IOException {
        if (isJar(input)) {
            return new JarProcessor(restorer).processJar(input, output, config);
        }
        return new ClassProcessor(restorer).processClass(input, output, config);
    }

    private void processDirectoryFile(Path input, Path backup, Path output) throws IOException {
        Files.createDirectories(backup.getParent());
        // Snapshot original bytes and attributes before invoking any processor.
        Files.copy(input, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Path temporary = Files.createTempFile(input.getParent(), ".classlinefix-", ".tmp");
        try {
            if (!transform(backup, temporary)) {
                Files.delete(backup);
                skippedFiles++;
                return;
            }
            Files.createDirectories(output.getParent());
            // Preserve original filesystem attributes on the replacement as well.
            copyAttributes(input, temporary);
            replace(temporary, input);
            Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
            processedFiles++;
            logger.info("Backed up and processed: {}", input);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void processSingleFile(Path input, Path output) throws IOException {
        Path temporary = Files.createTempFile(output.getParent(), ".classlinefix-", ".tmp");
        try {
            boolean modified = isCandidate(input) && transform(input, temporary);
            if (!modified) {
                // In particular, do not publish signature cleanup from an unchanged JAR.
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                skippedFiles++;
            } else {
                copyAttributes(input, temporary);
                processedFiles++;
            }
            replace(temporary, output);
            logger.info("Single-file output: {}", output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copyAttributes(Path source, Path target) throws IOException {
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
        if (Files.getFileAttributeView(source, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
        }
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
