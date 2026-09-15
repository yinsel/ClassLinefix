import com.killer.perfectlinerestorer.Main;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import java.io.File;
import java.util.jar.Manifest;
import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Tests standard CLASS metadata through the same Fernflower line-mapping API used by IDEA. */
public class FernflowerDebuggerSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("classlinefix-jdi-");
        VirtualMachine vm = null;
        Process target = null;
        try {
            Path input = Files.createDirectory(root.resolve("input"));
            Path output = root.resolve("input-out");
            Path source = root.resolve("DebugTarget.java");
            Files.write(source, ("public class DebugTarget {"
                    + "public static void main(String[] args) { System.out.println(calculate(7)); }"
                    + "static int calculate(int input) { int doubled=input*2; CharSequence text=\"value\"; if(input<0) text=new StringBuilder(\"other\"); return doubled+text.length(); }"
                    + "}").getBytes(StandardCharsets.UTF_8));
            require(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-g:none", "-d", input.toString(), source.toString()) == 0, "Fixture compilation failed");
            Main.main(new String[]{"-i", input.toString(), "-d", "-c"});
            require(Files.exists(output.resolve("DebugTarget.class")), "No repaired class produced");

            Mapping decompiled = decompile(output.resolve("DebugTarget.class"));
            int displayedFirst = decompiled.findLine("int var1 =");
            int displayedInterior = decompiled.findLine("var2 = \"value\"");

            LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("main").setValue("DebugTarget");
            arguments.get("options").setValue("-Xverify:all -cp \"" + output + "\"");
            vm = connector.launch(arguments);
            target = vm.process();
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("DebugTarget");
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            boolean breakpoint = false;
            boolean interiorBreakpoint = false;
            int firstLine = -1;
            int interiorLine = -1;
            boolean integerLocal = false;
            boolean stringLocal = false;
            boolean exited = false;
            Set<Integer> steppedLines = new HashSet<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!exited && System.nanoTime() < deadline) {
                EventSet events;
                try {
                    events = vm.eventQueue().remove(1000);
                } catch (VMDisconnectedException e) {
                    break;
                }
                if (events == null) continue;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent) {
                        ReferenceType type = ((ClassPrepareEvent) event).referenceType();
                        require("DebugTarget.java".equals(type.sourceName()), "SourceFile missing");
                        Method method = type.methodsByName("calculate").get(0);
                        List<Location> locations = method.allLineLocations();
                        firstLine = decompiled.sourceToBytecode(displayedFirst);
                        interiorLine = decompiled.sourceToBytecode(displayedInterior);
                        // Resolve explicit source line numbers; no MethodEntryRequest is used.
                        Location first = method.locationsOfLine(firstLine).get(0);
                        require(first.codeIndex() == 0, "First line is not the first executable instruction");
                        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(first);
                        request.putProperty("kind", "first-line");
                        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        request.enable();
                        BreakpointRequest interior = vm.eventRequestManager().createBreakpointRequest(method.locationsOfLine(interiorLine).get(0));
                        interior.putProperty("kind", "after-assignment");
                        interior.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        interior.enable();
                    } else if (event instanceof BreakpointEvent) {
                        BreakpointEvent hit = (BreakpointEvent) event;
                        StackFrame frame = hit.thread().frame(0);
                        if ("after-assignment".equals(hit.request().getProperty("kind"))) {
                            require(hit.location().lineNumber() == interiorLine, "Wrong interior breakpoint line");
                            LocalVariable local = frame.visibleVariableByName("var1");
                            require(local != null && ((IntegerValue) frame.getValue(local)).value() == 14,
                                    "Cannot read var1 at the explicit interior line breakpoint");
                            interiorBreakpoint = true;
                            hit.request().disable();
                            continue;
                        }
                        require(hit.location().lineNumber() == firstLine && hit.location().codeIndex() == 0,
                                "First-line breakpoint did not stop at bytecode offset 0");
                        require(frame.visibleVariableByName("var1") == null,
                                "Unassigned local must not appear at the first instruction");
                        LocalVariable parameter = frame.visibleVariableByName("arg0");
                        require(parameter != null && ((IntegerValue) frame.getValue(parameter)).value() == 7,
                                "Cannot read synthetic parameter arg0");
                        breakpoint = true;
                        StepRequest step = vm.eventRequestManager().createStepRequest(hit.thread(), StepRequest.STEP_LINE, StepRequest.STEP_OVER);
                        step.addClassFilter("DebugTarget");
                        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                        step.enable();
                        hit.request().disable();
                    } else if (event instanceof StepEvent) {
                        StepEvent step = (StepEvent) event;
                        if (!"calculate".equals(step.location().method().name())) {
                            step.request().disable();
                            continue;
                        }
                        int displayedLine = decompiled.bytecodeToSource(step.location().lineNumber());
                        require(displayedLine > 0, "Step stopped on a line with no decompiler mapping");
                        steppedLines.add(displayedLine);
                        StackFrame frame = step.thread().frame(0);
                        for (LocalVariable local : frame.visibleVariables()) {
                            Value value = frame.getValue(local);
                            if (local.name().equals("var1")) {
                                require(value instanceof IntegerValue && ((IntegerValue) value).value() == 14,
                                        "Incorrect local integer value");
                                integerLocal = true;
                            }
                            if (local.name().equals("var2")) {
                                require(value instanceof StringReference && ((StringReference) value).value().equals("value"),
                                        "Incorrect local reference value");
                                stringLocal = true;
                            }
                        }
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        exited = true;
                    }
                }
                if (!exited) events.resume();
            }
            require(breakpoint, "No line breakpoint hit");
            require(interiorBreakpoint, "No explicit interior line breakpoint hit");
            require(steppedLines.size() >= 2, "No distinct line step events");
            require(integerLocal && stringLocal, "Local variables were not readable while stepping");
            require(target.waitFor(5, TimeUnit.SECONDS) && target.exitValue() == 0, "Target did not exit successfully");
            String stdout = new String(target.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            require(stdout.equals("19"), "Program result changed: " + stdout);
            System.out.println("FERNFLOWER/JDI PASS: displayed first line=" + displayedFirst + ", JVM line=" + firstLine + " (bytecode offset 0), interior-line breakpoint="
                    + interiorLine + ", arg0=7, var1=14, var2=value, "
                    + steppedLines.size() + " distinct line steps, program result=19");
        } finally {
            if (vm != null) {
                try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
            }
            if (target != null && target.isAlive()) target.destroyForcibly();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    private static Mapping decompile(Path file) throws Exception {
        Mapping result = new Mapping();
        Map<String, Object> options = new HashMap<>();
        options.put("bsm", "1");
        options.put("udv", "1");
        byte[] before = Files.readAllBytes(file);
        BaseDecompiler engine = new BaseDecompiler((external, internal) -> Files.readAllBytes(Paths.get(external)),
                result, options, new PrintStreamLogger(System.err));
        engine.addSource(file.toFile());
        engine.decompileContext();
        require(result.mapping != null && result.source != null, "Fernflower did not recognize standard debug metadata");
        require(Arrays.equals(before, Files.readAllBytes(file)), "Decompiler modified the CLASS under test");
        return result;
    }

    private static final class Mapping implements IResultSaver {
        String source;
        int[] mapping;
        int findLine(String text) {
            String[] lines = source.split("\\n");
            for (int i = 0; i < lines.length; i++) if (lines[i].contains(text)) return i + 1;
            throw new AssertionError("Missing decompiled statement " + text + " in " + source);
        }
        // Same lookup semantics as IntelliJ LineNumbersMapping.ArrayBasedMapping.
        int sourceToBytecode(int line) {
            for (int i = 0; i < mapping.length; i += 2) if (mapping[i + 1] == line) return mapping[i];
            return -1;
        }
        int bytecodeToSource(int line) {
            for (int i = 0; i < mapping.length; i += 2) if (mapping[i] == line) return mapping[i + 1];
            return -1;
        }
        public void saveClassFile(String path, String name, String entry, String content, int[] lines) {
            source = content; mapping = lines;
        }
        public void saveFolder(String path) { }
        public void copyFile(String source, String path, String entry) { }
        public void createArchive(String path, String name, Manifest manifest) { }
        public void saveDirEntry(String path, String name, String entry) { }
        public void copyEntry(String source, String path, String name, String entry) { }
        public void saveClassEntry(String path, String archive, String name, String entry, String content) { }
        public void closeArchive(String path, String name) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
