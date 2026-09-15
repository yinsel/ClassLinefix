package com.killer.perfectlinerestorer;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileWorkflowTest {
    @TempDir
    Path temp;

    @Test
    void directoryBacksUpAndOverwritesOnlyChangedFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("in"));
        Path input = Files.createDirectories(root.resolve("nested"));
        Path output = temp.resolve("in-out");
        Path backup = temp.resolve("in-bak");
        byte[] selected = classBytes("com/example/Selected", false);
        Files.write(input.resolve("Selected.class"), selected);
        Files.write(input.resolve("Excluded.class"), classBytes("com/example/internal/Excluded", false));
        Files.write(input.resolve("Outside.class"), classBytes("org/other/Outside", false));
        Files.write(input.resolve("Lined.class"), classBytes("com/example/Lined", true));
        Files.write(input.resolve("Inner.class"), classBytes("com/example/Outer$Inner", false));
        Files.write(input.resolve("Broken.class"), new byte[]{1, 2, 3});
        Files.write(input.resolve("library.jar"), jarBytes(false));
        Files.write(input.resolve("config.txt"), new byte[]{4, 5, 6});
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", root.resolve(".").toString() + "/", "-c", "-w", "com.example",
                "-p", "com.example.internal", "-s", "true"});
        assertNotNull(config);
        new PerfectLineRestorer(config).process();
        assertEquals(Collections.singletonList("nested/Selected.class"), outputFiles(output));
        assertEquals(outputFiles(output), outputFiles(backup));
        assertArrayEquals(selected, Files.readAllBytes(backup.resolve("nested/Selected.class")));
        byte[] result = Files.readAllBytes(input.resolve("Selected.class"));
        assertTrue(new LineNumberRestorer().hasLineNumbers(result));
        assertArrayEquals(result, Files.readAllBytes(output.resolve("nested/Selected.class")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(input.resolve("Broken.class")));
        assertEquals(8, outputFiles(root).size()); // No temporary files remain in the input tree.
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directoryJarsKeepCompleteOriginalBackups(boolean alreadyLined) throws Exception {
        Path input = Files.createDirectories(temp.resolve("in/lib")).resolve("app.jar");
        byte[] original = jarBytes(alreadyLined);
        Files.write(input, original);
        new PerfectLineRestorer(Main.parseCommandLine(new String[]{"-i", temp.resolve("in").toString(),
                "-w", "com.example", "-p", "com.example.internal"})).process();
        Path backup = temp.resolve("in-bak/lib/app.jar");
        Path output = temp.resolve("in-out/lib/app.jar");
        assertEquals(!alreadyLined, Files.exists(output));
        assertEquals(!alreadyLined, Files.exists(backup));
        if (alreadyLined) {
            assertArrayEquals(original, Files.readAllBytes(input));
            assertFalse(Files.exists(temp.resolve("in-bak/lib")));
        } else {
            assertArrayEquals(original, Files.readAllBytes(backup));
            assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
            try (JarFile jar = new JarFile(output.toFile())) {
                assertTrue(new LineNumberRestorer().hasLineNumbers(readEntry(jar, "Selected.class")));
                assertArrayEquals(classBytes("com/example/internal/Excluded", false), readEntry(jar, "Excluded.class"));
                assertArrayEquals(new byte[]{7, 8, 9}, readEntry(jar, "resource.txt"));
            }
        }
    }

    @Test
    void packageFilteredClassesLeaveNoBackupDirectories() throws Exception {
        Path root = Files.createDirectory(temp.resolve("in"));
        // Paths intentionally disagree with bytecode names: selection must use the latter.
        Path outside = Files.createDirectories(root.resolve("com/example/deep")).resolve("Outside.class");
        Path excluded = Files.createDirectories(root.resolve("blocked/deep")).resolve("Excluded.class");
        Path selected = Files.createDirectories(root.resolve("unrelated/deep")).resolve("Selected.class");
        byte[] outsideBytes = classBytes("org/other/Outside", false);
        byte[] excludedBytes = classBytes("com/example/internal/Excluded", false);
        byte[] selectedBytes = classBytes("com/example/Selected", false);
        Files.write(outside, outsideBytes);
        Files.write(excluded, excludedBytes);
        Files.write(selected, selectedBytes);
        new PerfectLineRestorer(Main.parseCommandLine(new String[]{"-i", root.toString(),
                "-w", "com.example", "-p", "com.example.internal"})).process();
        for (String suffix : Arrays.asList("-bak", "-out")) {
            Path result = temp.resolve("in" + suffix);
            assertFalse(Files.exists(result.resolve("com")));
            assertFalse(Files.exists(result.resolve("blocked")));
            assertTrue(Files.exists(result.resolve("unrelated/deep/Selected.class")));
        }
        assertArrayEquals(outsideBytes, Files.readAllBytes(outside));
        assertArrayEquals(excludedBytes, Files.readAllBytes(excluded));
        assertArrayEquals(selectedBytes, Files.readAllBytes(temp.resolve("in-bak/unrelated/deep/Selected.class")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skippedClassesPruneEmptyParentsButKeepOtherBackups(boolean keepChangedFile) throws Exception {
        Path root = Files.createDirectory(temp.resolve("in"));
        Path nested = Files.createDirectories(root.resolve("shared/deep/unchanged"));
        Files.write(nested.resolve("Lined.class"), classBytes("com/example/Lined", true));
        Files.write(nested.resolve("Inner.class"), classBytes("com/example/Outer$Inner", false));
        byte[] original = classBytes("com/example/Selected", false);
        if (keepChangedFile) {
            Files.write(root.resolve("shared/Selected.class"), original);
        }
        new PerfectLineRestorer(Main.parseCommandLine(new String[]{"-i", root.toString(),
                "-w", "com.example", "-s", "true"})).process();
        Path backup = temp.resolve("in-bak");
        assertTrue(Files.isDirectory(backup));
        assertTrue(Files.isDirectory(temp.resolve("in-out")));
        assertFalse(Files.exists(backup.resolve("shared/deep")));
        assertEquals(keepChangedFile, Files.exists(backup.resolve("shared")));
        if (keepChangedFile) {
            assertArrayEquals(original, Files.readAllBytes(backup.resolve("shared/Selected.class")));
            assertArrayEquals(Files.readAllBytes(root.resolve("shared/Selected.class")),
                    Files.readAllBytes(temp.resolve("in-out/shared/Selected.class")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {".class", ".jar", ".CLASS", ".JAR"})
    void singleFileUsesFixSuffixAndPreservesInput(String extension) throws Exception {
        Path input = temp.resolve("my.app" + extension);
        byte[] original = extension.equalsIgnoreCase(".jar") ? jarBytes(false) : classBytes("Selected", false);
        Files.write(input, original);
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{"-i", input.toString()});
        Path output = temp.resolve("my.app-fix" + extension);
        assertEquals(output.toString(), config.getOutputDir());
        Files.write(output, new byte[]{9, 8, 7});
        new PerfectLineRestorer(config).process();
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Arrays.equals(original, Files.readAllBytes(output)));
        assertFalse(Files.exists(temp.resolve("my.app" + extension + "-bak")));
    }

    @Test
    void unchangedSingleJarRemainsByteIdenticalIncludingSignatures() throws Exception {
        Path input = temp.resolve("app.jar");
        byte[] original = jarBytes(true);
        Files.write(input, original);
        new PerfectLineRestorer(Main.parseCommandLine(new String[]{"-i", input.toString(),
                "-p", "com.example.internal"})).process();
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("app-fix.jar")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-bak", "-out"})
    void existingRunDirectoriesAreRejectedBeforeInputChanges(String suffix) throws Exception {
        Path input = Files.createDirectory(temp.resolve("in"));
        byte[] original = classBytes("Selected", false);
        Files.write(input.resolve("Selected.class"), original);
        Path previous = Files.createDirectory(temp.resolve("in" + suffix));
        Files.write(previous.resolve("Selected.class"), new byte[]{9});
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> new PerfectLineRestorer(new Main.CommandLineConfig(input.toString())).process());
        assertArrayEquals(original, Files.readAllBytes(input.resolve("Selected.class")));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(previous.resolve("Selected.class")));
    }

    @Test
    void corruptJarFailureKeepsOriginalAndBackup() throws Exception {
        Path root = Files.createDirectory(temp.resolve("in"));
        Path input = root.resolve("broken.jar");
        byte[] truncated = Arrays.copyOf(jarBytes(false), 45);
        Files.write(input, truncated);
        assertThrows(IOException.class,
                () -> new PerfectLineRestorer(new Main.CommandLineConfig(root.toString())).process());
        assertArrayEquals(truncated, Files.readAllBytes(input));
        assertArrayEquals(truncated, Files.readAllBytes(temp.resolve("in-bak/broken.jar")));
        assertFalse(Files.exists(temp.resolve("in-out/broken.jar")));
        assertEquals(Collections.singletonList("broken.jar"), outputFiles(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-o", "--output", "-m", "--modified-only", "--modify-only"})
    void removedOptionsAreRejected(String option) {
        assertNull(Main.parseCommandLine(new String[]{"-i", "input", option}));
    }

    private List<String> outputFiles(Path output) throws IOException {
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> output.relativize(path).toString().replace('\\', '/'))
                    .sorted().collect(Collectors.toList());
        }
    }

    private byte[] readEntry(JarFile jar, String name) throws IOException {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(name))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private byte[] jarBytes(boolean alreadyLined) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("Selected.class"));
            jar.write(classBytes("com/example/Selected", alreadyLined));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("Excluded.class"));
            jar.write(classBytes("com/example/internal/Excluded", false));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("resource.txt"));
            jar.write(new byte[]{7, 8, 9});
            jar.closeEntry();
            // Removing a signature alone must not qualify an archive for output.
            jar.putNextEntry(new JarEntry("META-INF/TEST.SF"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] classBytes(String name, boolean lined) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        method.visitCode();
        if (lined) {
            Label label = new Label();
            method.visitLabel(label);
            method.visitLineNumber(10, label);
        }
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
