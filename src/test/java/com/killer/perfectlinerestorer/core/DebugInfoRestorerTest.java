package com.killer.perfectlinerestorer.core;

import com.killer.perfectlinerestorer.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DebugInfoRestorerTest {
    @TempDir
    Path temp;

    private LineNumberRestorer restorer() {
        return new LineNumberRestorer(new Main.CommandLineConfig("in", null, null,
                false, false, true, false));
    }

    private byte[] compile(String debug, boolean parameters) throws Exception {
        String source = "public class Subject {"
                + "static int initialized; static { initialized = 3; } public Subject() {}"
                + "public static int calculate(int input) { int doubled=input*2; String text=\"value\"; return doubled+text.length(); }"
                + "public static long wide(long number, double factor) { long result=number+(long)factor; return result; }"
                + "public static int branch(boolean flag) { Object value; if(flag) value=\"one\"; else value=new StringBuilder(\"two\"); return value.toString().length(); }"
                + "public int caught(boolean flag) { try { String value=\"x\"; if(flag) throw new IllegalArgumentException(); return value.length(); } catch(RuntimeException error) { return error.getClass().getName().length(); } }"
                + "public static java.util.Map references(boolean flag) { java.util.Map value=new java.util.HashMap(); try { if(flag) throw new IllegalArgumentException(); value=new java.util.TreeMap(); } catch(RuntimeException failure) { value.put(\"error\",failure); } return value; }"
                + "public static Object[] arrays(String[][] values) { Object[] sub=values[0]; return sub; }"
                + "}";
        Path file = temp.resolve("Subject.java");
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        List<String> args = new ArrayList<>(Arrays.asList(debug, "-source", "8", "-target", "8", "-d", temp.toString()));
        if (parameters) args.add("-parameters");
        args.add(file.toString());
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])));
        return Files.readAllBytes(temp.resolve("Subject.class"));
    }

    @Test
    void fillsMissingMetadataPreservesInstructionsAndRunsIdentically() throws Exception {
        byte[] original = compile("-g:none", false);
        byte[] result = restorer().restoreLineNumbers(original);
        ClassNode node = read(result);
        assertEquals("Subject.java", node.sourceFile);
        for (MethodNode method : node.methods) {
            assertTrue(lines(method).size() > 0, method.name);
        }
        MethodNode calculate = method(node, "calculate");
        assertTrue(calculate.localVariables.stream().anyMatch(v -> v.name.equals("arg0") && v.index == 0 && v.desc.equals("I")));
        assertTrue(calculate.localVariables.stream().anyMatch(v -> v.index == 1 && v.desc.equals("I")));
        assertTrue(calculate.localVariables.stream().anyMatch(v -> v.index == 2 && v.desc.equals("Ljava/lang/String;")));
        MethodNode wide = method(node, "wide");
        assertTrue(wide.localVariables.stream().anyMatch(v -> v.name.equals("arg0") && v.index == 0 && v.desc.equals("J")));
        assertTrue(wide.localVariables.stream().anyMatch(v -> v.name.equals("arg1") && v.index == 2 && v.desc.equals("D")));
        assertFalse(wide.localVariables.stream().anyMatch(v -> v.index == 1 || v.index == 3));
        assertEquals(withoutDebug(original), withoutDebug(result));
        assertArrayEquals(result, restorer().restoreLineNumbers(result), "A second pass must be a no-op");
        Class<?> before = load(original);
        Class<?> after = load(result);
        assertEquals(19, after.getMethod("calculate", int.class).invoke(null, 7));
        assertEquals(13L, after.getMethod("wide", long.class, double.class).invoke(null, 7L, 6.0));
        for (boolean flag : new boolean[]{true, false}) {
            assertEquals(before.getMethod("branch", boolean.class).invoke(null, flag),
                    after.getMethod("branch", boolean.class).invoke(null, flag));
            assertEquals(before.getMethod("caught", boolean.class).invoke(before.getConstructor().newInstance(), flag),
                    after.getMethod("caught", boolean.class).invoke(after.getConstructor().newInstance(), flag));
        }
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(result), after.getClassLoader(), false, new PrintWriter(diagnostics));
        assertEquals("", diagnostics.toString());
    }

    @Test
    void usesStatementBoundariesAndKeepsContinuousReferenceNamesStable() throws Exception {
        ClassNode result = read(restorer().restoreLineNumbers(compile("-g:none", false)));
        assertEquals(3, lines(method(result, "calculate")).size(),
                "A load/multiply/store expression must share one synthetic line");
        List<LocalVariableNode> locals = method(result, "references").localVariables.stream()
                .filter(v -> v.index == 1).collect(Collectors.toList());
        assertEquals(1, locals.size(), "One continuous reference slot must not acquire multiple debug names");
        assertEquals("var1", locals.get(0).name);
        assertEquals("Ljava/lang/Object;", locals.get(0).desc);
    }

    @Test
    void rebuildingExistingLinesIsExplicitAndRepeatable() throws Exception {
        byte[] original = compile("-g:source,lines", false);
        LineNumberRestorer rebuild = new LineNumberRestorer(new Main.CommandLineConfig("in", null, null,
                false, false, false, true));
        byte[] result = rebuild.restoreLineNumbers(original);
        assertEquals(3, lines(method(read(result), "calculate")).size());
        assertEquals(withoutDebug(original), withoutDebug(result));
        assertArrayEquals(result, rebuild.restoreLineNumbers(result));
    }

    @Test
    void existingLineNumbersDoNotPreventLocalVariableRepair() throws Exception {
        byte[] original = compile("-g:source,lines", false);
        byte[] result = restorer().restoreLineNumbers(original);
        assertFalse(Arrays.equals(original, result));
        ClassNode before = read(original);
        ClassNode after = read(result);
        for (int i = 0; i < before.methods.size(); i++) {
            assertEquals(lines(before.methods.get(i)), lines(after.methods.get(i)));
        }
        assertFalse(method(after, "calculate").localVariables.isEmpty());
        assertEquals(withoutDebug(original), withoutDebug(result));
    }

    @Test
    void preservesExistingNamesAndFillsOnlyUncoveredRanges() throws Exception {
        ClassNode original = read(compile("-g", false));
        MethodNode method = method(original, "calculate");
        method.localVariables.removeIf(local -> local.name.equals("text"));
        List<String> originalLocals = method.localVariables.stream().map(local -> local.name).collect(Collectors.toList());
        byte[] result = restorer().restoreLineNumbers(write(original));
        MethodNode repaired = method(read(result), "calculate");
        for (String name : originalLocals) {
            assertTrue(repaired.localVariables.stream().anyMatch(local -> local.name.equals(name)));
        }
        assertTrue(repaired.localVariables.stream().anyMatch(local -> local.index == 2 && local.desc.equals("Ljava/lang/String;")));
        assertEquals(lines(method), lines(repaired));
        assertArrayEquals(result, restorer().restoreLineNumbers(result));
    }

    @Test
    void usesMethodParameterNamesWhenPresent() throws Exception {
        ClassNode node = read(restorer().restoreLineNumbers(compile("-g:none", true)));
        assertTrue(method(node, "calculate").localVariables.stream().anyMatch(v -> v.name.equals("input")));
    }

    @Test
    void splitsReusedSlotsAndDoesNotExposeUninitializedObjects() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Reuse", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 0);
        method.visitIincInsn(0, 1);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] result = restorer().restoreLineNumbers(writer.toByteArray());
        MethodNode repaired = method(read(result), "value");
        assertTrue(repaired.localVariables.stream().anyMatch(v -> v.index == 0 && v.desc.equals("I")));
        int initialization = -1;
        for (int i = 0; i < repaired.instructions.size(); i++) {
            if (repaired.instructions.get(i).getOpcode() == Opcodes.INVOKESPECIAL) initialization = i;
        }
        final int init = initialization;
        assertTrue(repaired.localVariables.stream().anyMatch(v -> v.index == 0
                && v.desc.equals("Ljava/lang/StringBuilder;") && repaired.instructions.indexOf(v.start) > init));
        assertTrue(load(result).getMethod("value").invoke(null) instanceof StringBuilder);
        assertArrayEquals(result, restorer().restoreLineNumbers(result));
    }

    @Test
    void constructorThisStartsAfterInitialization() throws Exception {
        MethodNode constructor = method(read(restorer().restoreLineNumbers(compile("-g:none", false))), "<init>");
        int initialization = -1;
        for (int i = 0; i < constructor.instructions.size(); i++) {
            if (constructor.instructions.get(i).getOpcode() == Opcodes.INVOKESPECIAL) initialization = i;
        }
        LocalVariableNode self = constructor.localVariables.stream().filter(v -> v.name.equals("this")).findFirst().get();
        assertTrue(constructor.instructions.indexOf(self.start) > initialization);
    }

    @Test
    void respectsSlotsDroppedByExistingStackMapFrames() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Dropped", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "(I)I", null, null);
        method.visitCode();
        method.visitLdcInsn("temporary");
        method.visitVarInsn(Opcodes.ASTORE, 1);
        Label join = new Label();
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(join);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
        writer.visitEnd();
        byte[] result = restorer().restoreLineNumbers(writer.toByteArray());
        MethodNode repaired = method(read(result), "value");
        int returnLoad = -1;
        for (int i = 0; i < repaired.instructions.size(); i++) {
            if (repaired.instructions.get(i).getOpcode() == Opcodes.ILOAD) returnLoad = i;
        }
        final int end = returnLoad;
        assertTrue(repaired.localVariables.stream().anyMatch(v -> v.index == 1));
        assertTrue(repaired.localVariables.stream().filter(v -> v.index == 1)
                .allMatch(v -> repaired.instructions.indexOf(v.end) <= end));
        assertEquals(7, load(result).getMethod("value", int.class).invoke(null, 7));
        assertArrayEquals(result, restorer().restoreLineNumbers(result));
    }

    @Test
    void handlesReferencesWithoutLoadingApplicationDependencies() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Absent", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "identity",
                "(Lmissing/Dependency;)Lmissing/Dependency;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        MethodNode result = method(read(restorer().restoreLineNumbers(writer.toByteArray())), "identity");
        assertTrue(result.localVariables.stream().anyMatch(v -> v.name.equals("arg0") && v.desc.equals("Lmissing/Dependency;")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-d", "--debug-info"})
    void cliRepairsLinedClassesAndSkipsSecondPass(String flag) throws Exception {
        byte[] original = compile("-g:source,lines", false);
        Path input = Files.createDirectory(temp.resolve("input"));
        Path output = temp.resolve("input-out");
        Files.write(input.resolve("Subject.class"), original);
        Main.main(new String[]{"-i", input.toString(), "-c", flag});
        byte[] result = Files.readAllBytes(output.resolve("Subject.class"));
        assertFalse(method(read(result), "calculate").localVariables.isEmpty());
        Path second = temp.resolve("input-out-out");
        Main.main(new String[]{"-i", output.toString(), "-c", flag});
        assertFalse(Files.exists(second.resolve("Subject.class")));
    }

    private Class<?> load(byte[] bytes) {
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().get();
    }

    private List<Integer> lines(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LineNumberNode) result.add(((LineNumberNode) instruction).line);
        }
        return result;
    }

    private String withoutDebug(byte[] bytes) {
        StringWriter text = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(text)), ClassReader.SKIP_DEBUG);
        return text.toString();
    }
}
