package com.killer.perfectlinerestorer;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClassOnlyTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"-c", "--class-only"})
    void processesStandaloneClassesAndPreservesWholeArchives(String flag) throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        Path output = temp.resolve("output");
        Path lib = Files.createDirectory(input.resolve("lib"));
        byte[] selected = classBytes("com/example/Selected");
        byte[] excluded = classBytes("com/example/internal/Excluded");
        byte[] outside = classBytes("org/other/Outside");
        byte[] archive = jarBytes(selected);
        byte[] resource = new byte[]{1, 2, 3};
        Files.write(input.resolve("Selected.class"), selected);
        Files.write(input.resolve("Excluded.class"), excluded);
        Files.write(input.resolve("Outside.class"), outside);
        Files.write(lib.resolve("library.JAR"), archive);
        // Even an unreadable archive must be copied without being opened.
        Files.write(lib.resolve("invalid.jar"), resource);
        Files.write(input.resolve("config.txt"), resource);
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", input.toString(), "-o", output.toString(), flag,
                "-w", "com.example", "-p", "com.example.internal", "-s", "true"});
        assertNotNull(config);
        assertTrue(config.isClassOnly());
        assertTrue(config.isSkipInnerClasses());
        new PerfectLineRestorer(config).process();
        assertTrue(new LineNumberRestorer().hasLineNumbers(Files.readAllBytes(output.resolve("Selected.class"))));
        assertArrayEquals(excluded, Files.readAllBytes(output.resolve("Excluded.class")));
        assertArrayEquals(outside, Files.readAllBytes(output.resolve("Outside.class")));
        assertArrayEquals(archive, Files.readAllBytes(output.resolve("lib/library.JAR")));
        assertArrayEquals(resource, Files.readAllBytes(output.resolve("lib/invalid.jar")));
        assertArrayEquals(resource, Files.readAllBytes(output.resolve("config.txt")));
    }

    @Test
    void singleJarInputOverwritesExistingOutputWithoutProcessing() throws Exception {
        Path input = temp.resolve("input.jar");
        Path output = temp.resolve("output.jar");
        byte[] archive = jarBytes(classBytes("com/example/Selected"));
        Files.write(input, archive);
        // Same size and newer timestamp must not retain a previously modified output.
        byte[] changed = archive.clone();
        changed[0] ^= 1;
        Files.write(output, changed);
        Files.setLastModifiedTime(output, FileTime.fromMillis(Files.getLastModifiedTime(input).toMillis() + 10000));
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", input.toString(), "-o", output.toString(), "--class-only"});
        new PerfectLineRestorer(config).process();
        assertArrayEquals(archive, Files.readAllBytes(output));
    }

    @Test
    void defaultStillProcessesJars() throws Exception {
        Path input = temp.resolve("input.jar");
        Path output = temp.resolve("output.jar");
        byte[] archive = jarBytes(classBytes("com/example/Selected"));
        Files.write(input, archive);
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", input.toString(), "-o", output.toString()});
        assertFalse(config.isClassOnly());
        assertFalse(new Main.CommandLineConfig("in", "out").isClassOnly());
        new PerfectLineRestorer(config).process();
        assertFalse(java.util.Arrays.equals(archive, Files.readAllBytes(output)));
    }

    private byte[] jarBytes(byte[] classBytes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("com/example/Selected.class"));
            jar.write(classBytes);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/TEST.SF"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] classBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
