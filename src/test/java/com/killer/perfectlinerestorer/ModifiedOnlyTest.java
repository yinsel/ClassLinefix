package com.killer.perfectlinerestorer;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

class ModifiedOnlyTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"-m", "--modified-only"})
    void onlyPublishesChangedStandaloneClasses(String flag) throws Exception {
        Path input = Files.createDirectories(temp.resolve("in/nested"));
        Path output = temp.resolve("out");
        Files.write(input.resolve("Selected.class"), classBytes("com/example/Selected", false));
        Files.write(input.resolve("Excluded.class"), classBytes("com/example/internal/Excluded", false));
        Files.write(input.resolve("Outside.class"), classBytes("org/other/Outside", false));
        Files.write(input.resolve("Lined.class"), classBytes("com/example/Lined", true));
        Files.write(input.resolve("Inner.class"), classBytes("com/example/Outer$Inner", false));
        Files.write(input.resolve("Broken.class"), new byte[]{1, 2, 3});
        Files.write(input.resolve("library.jar"), jarBytes(false));
        Files.write(input.resolve("config.txt"), new byte[]{4, 5, 6});
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", temp.resolve("in").toString(), "-o", output.toString(),
                flag, "-c", "-w", "com.example", "-p", "com.example.internal", "-s", "true"});
        assertNotNull(config);
        assertTrue(config.isModifiedOnly());
        new PerfectLineRestorer(config).process();
        assertEquals(Collections.singletonList("nested/Selected.class"), outputFiles(output));
        assertTrue(new LineNumberRestorer().hasLineNumbers(Files.readAllBytes(output.resolve("nested/Selected.class"))));
    }

    @ParameterizedTest
    @CsvSource({"false, false, true", "true, false, false", "false, true, false"})
    void onlyPublishesCompleteJarsWithChangedClasses(boolean alreadyLined, boolean classOnly,
                                                   boolean expectedOutput) throws Exception {
        Path input = temp.resolve("input.jar");
        Path output = temp.resolve("out/result.jar");
        Files.write(input, jarBytes(alreadyLined));
        String[] args = {"-i", input.toString(), "-o", output.toString(), "-m",
                "-w", "com.example", "-p", "com.example.internal"};
        if (classOnly) {
            args = Arrays.copyOf(args, args.length + 1);
            args[args.length - 1] = "-c";
        }
        Main.CommandLineConfig config = Main.parseCommandLine(args);
        new PerfectLineRestorer(config).process();
        assertEquals(expectedOutput, Files.exists(output));
        if (expectedOutput) {
            try (JarFile jar = new JarFile(output.toFile())) {
                assertTrue(new LineNumberRestorer().hasLineNumbers(readEntry(jar, "Selected.class")));
                assertArrayEquals(classBytes("com/example/internal/Excluded", false), readEntry(jar, "Excluded.class"));
                assertArrayEquals(new byte[]{7, 8, 9}, readEntry(jar, "resource.txt"));
            }
        }
    }

    @Test
    void skippedFilesLeaveExistingOutputsUntouched() throws Exception {
        Path input = Files.createDirectory(temp.resolve("in"));
        Path output = Files.createDirectory(temp.resolve("out"));
        Files.write(input.resolve("Lined.class"), classBytes("com/example/Lined", true));
        Files.write(input.resolve("resource.txt"), new byte[]{1});
        byte[] previous = {9, 8, 7};
        Files.write(output.resolve("Lined.class"), previous);
        Files.write(output.resolve("resource.txt"), previous);
        new PerfectLineRestorer(Main.parseCommandLine(new String[]{
                "-i", input.toString(), "-o", output.toString(), "-m"})).process();
        assertArrayEquals(previous, Files.readAllBytes(output.resolve("Lined.class")));
        assertArrayEquals(previous, Files.readAllBytes(output.resolve("resource.txt")));
    }

    @Test
    void failedJarDoesNotReplaceExistingOutput() throws Exception {
        Path input = temp.resolve("missing.jar");
        Path output = temp.resolve("existing.jar");
        byte[] previous = {9, 8, 7};
        Files.write(output, previous);
        PerfectLineRestorer restorer = new PerfectLineRestorer(Main.parseCommandLine(new String[]{
                "-i", input.toString(), "-o", output.toString(), "-m"}));
        assertThrows(IOException.class, restorer::process);
        assertArrayEquals(previous, Files.readAllBytes(output));
    }

    @Test
    void defaultAndExistingConstructorsKeepCopyingBehavior() throws Exception {
        Path input = Files.createDirectory(temp.resolve("in"));
        Path output = temp.resolve("out");
        byte[] lined = classBytes("com/example/Lined", true);
        Files.write(input.resolve("Lined.class"), lined);
        Files.write(input.resolve("resource.txt"), new byte[]{1});
        Main.CommandLineConfig config = new Main.CommandLineConfig(input.toString(), output.toString(),
                null, null, false, true);
        assertFalse(config.isModifiedOnly());
        assertFalse(Main.parseCommandLine(new String[]{"-i", "in", "-o", "out"}).isModifiedOnly());
        new PerfectLineRestorer(config).process();
        assertArrayEquals(lined, Files.readAllBytes(output.resolve("Lined.class")));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(output.resolve("resource.txt")));
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
