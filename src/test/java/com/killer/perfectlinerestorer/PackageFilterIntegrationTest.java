package com.killer.perfectlinerestorer;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import com.killer.perfectlinerestorer.processor.JarProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackageFilterIntegrationTest {
    @TempDir
    Path temp;

    private Map<String, byte[]> fixtures() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        // Deliberately unrelated file names: filtering must use the bytecode class name.
        files.put("renamed.class", classBytes("com/example/Selected"));
        files.put("sub/blocked.class", classBytes("com/example/internal/Blocked"));
        files.put("other.class", classBytes("com/examples/Other"));
        files.put("resource.txt", new byte[]{1, 2, 3});
        return files;
    }

    private Main.CommandLineConfig config(Path input, Path output) {
        return Main.parseCommandLine(new String[]{"-i", input.toString(),
                "-w", "com.example", "-p", "com.example.internal"});
    }

    @Test
    void directoryProcessingPreservesUnselectedClassesAndResources() throws Exception {
        Path input = Files.createDirectory(temp.resolve("in"));
        Path output = temp.resolve("in-out");
        Map<String, byte[]> files = fixtures();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path path = input.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, entry.getValue());
        }
        new PerfectLineRestorer(config(input, output)).process();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            verify(entry.getKey(), entry.getValue(), Files.readAllBytes(input.resolve(entry.getKey())));
            if ("renamed.class".equals(entry.getKey())) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(temp.resolve("in-bak").resolve(entry.getKey())));
                assertArrayEquals(Files.readAllBytes(input.resolve(entry.getKey())), Files.readAllBytes(output.resolve(entry.getKey())));
            } else {
                assertFalse(Files.exists(output.resolve(entry.getKey())));
                assertFalse(Files.exists(temp.resolve("in-bak").resolve(entry.getKey())));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jarAndZipFallbackApplyTheSameFilters(boolean zipFallback) throws Exception {
        Path input = temp.resolve("in.jar");
        Path output = temp.resolve("out.jar");
        Map<String, byte[]> files = fixtures();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        Main.CommandLineConfig config = config(input, output);
        JarProcessor processor = new JarProcessor(new LineNumberRestorer(config));
        if (zipFallback) {
            // Exercise the fallback directly without introducing a signed JAR fixture.
            Method fallback = JarProcessor.class.getDeclaredMethod("processJarWithZipStream",
                    Path.class, Path.class, Main.CommandLineConfig.class);
            fallback.setAccessible(true);
            assertEquals(Boolean.TRUE, fallback.invoke(processor, input, output, config));
        } else {
            assertTrue(processor.processJar(input, output, config));
        }
        try (JarFile jar = new JarFile(output.toFile())) {
            assertEquals(files.size(), jar.size());
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                try (InputStream stream = jar.getInputStream(jar.getJarEntry(entry.getKey()))) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = stream.read(buffer)) != -1) {
                        bytes.write(buffer, 0, length);
                    }
                    verify(entry.getKey(), entry.getValue(), bytes.toByteArray());
                }
            }
        }
    }

    private void verify(String name, byte[] original, byte[] actual) {
        if ("renamed.class".equals(name)) {
            assertFalse(new LineNumberRestorer().hasLineNumbers(original));
            assertTrue(new LineNumberRestorer().hasLineNumbers(actual));
        } else {
            assertArrayEquals(original, actual, name);
        }
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
