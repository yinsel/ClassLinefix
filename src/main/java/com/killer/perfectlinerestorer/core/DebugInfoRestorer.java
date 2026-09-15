package com.killer.perfectlinerestorer.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Adds synthetic debugger metadata without changing executable instructions. */
final class DebugInfoRestorer {
    private static final Logger logger = LoggerFactory.getLogger(DebugInfoRestorer.class);

    boolean restore(ClassNode owner) {
        boolean modified = false;
        if (owner.sourceFile == null) {
            String simpleName = owner.name.substring(owner.name.lastIndexOf('/') + 1);
            int inner = simpleName.indexOf('$');
            owner.sourceFile = (inner < 0 ? simpleName : simpleName.substring(0, inner)) + ".java";
            modified = true;
        }
        int nextLine = 1;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode) {
                    nextLine = Math.max(nextLine, ((LineNumberNode) instruction).line + 1);
                }
            }
        }
        for (MethodNode method : owner.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || method.instructions.size() == 0) {
                continue;
            }
            // Collect statement boundaries before locals add labels and change frame indices.
            Set<AbstractInsnNode> statementStarts = Collections.newSetFromMap(new IdentityHashMap<>());
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() >= 0) { statementStarts.add(instruction); break; }
            }
            try {
                Frame<BasicValue>[] frames = analyze(owner.name, method);
                for (int i = 0; i < method.instructions.size(); i++) {
                    AbstractInsnNode instruction = method.instructions.get(i);
                    int opcode = instruction.getOpcode();
                    if (opcode >= 0 && opcode != Opcodes.GOTO && opcode != Opcodes.NOP
                            && frames[i] != null && frames[i].getStackSize() == 0) {
                        statementStarts.add(instruction);
                    }
                }
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    AbstractInsnNode handler = block.handler;
                    while (handler != null && handler.getOpcode() < 0) handler = handler.getNext();
                    if (handler != null) statementStarts.add(handler);
                }
                modified |= restoreLocals(owner.name, method, frames);
            } catch (AnalyzerException | RuntimeException e) {
                logger.warn("Cannot infer locals for {}.{}{}; retaining existing locals: {}",
                        owner.name, method.name, method.desc, e.getMessage());
            }
            boolean hasLines = false;
            for (AbstractInsnNode instruction : method.instructions) {
                hasLines |= instruction instanceof LineNumberNode;
            }
            if (!hasLines) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (statementStarts.contains(instruction) && nextLine <= 65535) {
                        LabelNode label = boundary(method, instruction);
                        method.instructions.insert(label, new LineNumberNode(nextLine++, label));
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    private Frame<BasicValue>[] analyze(String owner, MethodNode method) throws AnalyzerException {
        Map<Integer, boolean[]> frameMasks = frameMasks(owner, method);
        DebugInterpreter interpreter = new DebugInterpreter("<init>".equals(method.name));
        Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(interpreter) {
            @Override
            protected void newControlFlowEdge(int instruction, int successor) {
                boolean[] mask = frameMasks.get(successor);
                Frame<BasicValue> frame = getFrames()[successor];
                if (mask != null && frame != null) {
                    // An explicit StackMapTable TOP overrides an inferred stale slot value.
                    for (int slot = 0; slot < mask.length; slot++) {
                        if (!mask[slot]) frame.setLocal(slot, BasicValue.UNINITIALIZED_VALUE);
                    }
                }
            }

            @Override
            protected Frame<BasicValue> newFrame(int locals, int stack) {
                return new DebugFrame(locals, stack);
            }

            @Override
            protected Frame<BasicValue> newFrame(Frame<? extends BasicValue> frame) {
                return new DebugFrame(frame);
            }
        };
        return analyzer.analyze(owner, method);
    }

    private boolean restoreLocals(String owner, MethodNode method, Frame<BasicValue>[] frames) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        List<Integer> executable = new ArrayList<>();
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < instructions.length; i++) {
            indices.put(instructions[i], i);
            if (instructions[i].getOpcode() >= 0) {
                executable.add(i);
            }
        }
        List<LocalVariableNode> existing = method.localVariables == null
                ? Collections.emptyList() : method.localVariables;
        Map<Integer, String> parameterNames = new HashMap<>();
        Map<Integer, Type> parameterTypes = new HashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            parameterNames.put(0, "this");
            parameterTypes.put(0, Type.getObjectType(owner));
            slot = 1;
        }
        Type[] arguments = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < arguments.length; i++) {
            String name = method.parameters != null && i < method.parameters.size()
                    ? method.parameters.get(i).name : null;
            parameterNames.put(slot, name == null || name.isEmpty() ? "arg" + i : name);
            parameterTypes.put(slot, arguments[i]);
            slot += arguments[i].getSize();
        }
        List<LocalRange> ranges = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (LocalVariableNode local : existing) {
            names.add(local.name);
        }
        for (int index = 0; index < method.maxLocals; index++) {
            String current = null;
            int start = 0;
            int segment = 0;
            for (int position = 0; position <= executable.size(); position++) {
                String descriptor = null;
                if (position < executable.size()) {
                    int instructionIndex = executable.get(position);
                    Frame<BasicValue> frame = frames[instructionIndex];
                    if (frame != null && !covered(existing, indices, index, instructionIndex)) {
                        descriptor = descriptor(frame.getLocal(index));
                    }
                }
                // A continuously live reference slot is one synthetic variable, even when
                // inference changes HashMap -> Map -> Object across assignments/joins.
                // Splitting on those types makes decompilers pick a later name for the
                // whole variable, which is then unavailable at earlier breakpoints.
                if (isReferenceDescriptor(current) && isReferenceDescriptor(descriptor)) {
                    if (!current.equals(descriptor)) current = "Ljava/lang/Object;";
                    continue;
                }
                if (!Objects.equals(current, descriptor)) {
                    if (current != null) {
                        Type parameterType = parameterTypes.get(index);
                        String base = segment == 0 && parameterType != null
                                && parameterType.getDescriptor().equals(current)
                                ? parameterNames.get(index) : "var" + index;
                        String name = base;
                        int suffix = 1;
                        while (!names.add(name)) {
                            name = base + "_" + suffix++;
                        }
                        ranges.add(new LocalRange(name, current, index,
                                instructions[executable.get(start)],
                                position == executable.size() ? null : instructions[executable.get(position)]));
                        segment++;
                    }
                    current = descriptor;
                    start = position;
                }
            }
        }
        if (ranges.isEmpty()) {
            return false;
        }
        if (method.localVariables == null) {
            method.localVariables = new ArrayList<>();
        }
        LabelNode end = null;
        for (LocalRange range : ranges) {
            LabelNode finish;
            if (range.end == null) {
                if (end == null) {
                    end = new LabelNode();
                    method.instructions.add(end);
                }
                finish = end;
            } else {
                finish = boundary(method, range.end);
            }
            method.localVariables.add(new LocalVariableNode(range.name, range.descriptor, null,
                    boundary(method, range.start), finish, range.slot));
        }
        return true;
    }

    /** Decode compressed stack-map locals; a long/double entry consumes two slots. */
    private Map<Integer, boolean[]> frameMasks(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals.add("<init>".equals(method.name) ? Opcodes.UNINITIALIZED_THIS : owner);
        }
        for (Type type : Type.getArgumentTypes(method.desc)) {
            switch (type.getSort()) {
                case Type.LONG: locals.add(Opcodes.LONG); break;
                case Type.DOUBLE: locals.add(Opcodes.DOUBLE); break;
                case Type.FLOAT: locals.add(Opcodes.FLOAT); break;
                case Type.ARRAY: locals.add(type.getDescriptor()); break;
                case Type.OBJECT: locals.add(type.getInternalName()); break;
                default: locals.add(Opcodes.INTEGER);
            }
        }
        Map<Integer, boolean[]> masks = new HashMap<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof FrameNode)) continue;
            FrameNode frame = (FrameNode) instruction;
            if (frame.type == Opcodes.F_NEW || frame.type == Opcodes.F_FULL) {
                locals = new ArrayList<>(frame.local);
            } else if (frame.type == Opcodes.F_APPEND) {
                locals.addAll(frame.local);
            } else if (frame.type == Opcodes.F_CHOP) {
                for (int i = 0; i < frame.local.size(); i++) locals.remove(locals.size() - 1);
            }
            boolean[] mask = new boolean[method.maxLocals];
            int slot = 0;
            for (Object local : locals) {
                if (slot >= mask.length) break;
                // Keep tracked uninitialized values so constructor calls can initialize aliases.
                mask[slot++] = !Opcodes.TOP.equals(local);
                if (Opcodes.LONG.equals(local) || Opcodes.DOUBLE.equals(local)) slot++;
            }
            masks.put(index, mask);
        }
        return masks;
    }

    private boolean covered(List<LocalVariableNode> existing, Map<AbstractInsnNode, Integer> indices,
                            int slot, int instruction) {
        for (LocalVariableNode local : existing) {
            Integer start = indices.get(local.start);
            Integer end = indices.get(local.end);
            if (local.index == slot && start != null && end != null && start <= instruction && instruction < end) {
                return true;
            }
        }
        return false;
    }

    private boolean isReferenceDescriptor(String descriptor) {
        return descriptor != null && (descriptor.startsWith("L") || descriptor.startsWith("["));
    }

    private String descriptor(BasicValue value) {
        if (value == null || value instanceof UninitializedValue || value.getType() == null
                || BasicInterpreter.NULL_TYPE.equals(value.getType())) {
            return null;
        }
        return value.getType().getDescriptor();
    }

    private static LabelNode boundary(MethodNode method, AbstractInsnNode instruction) {
        if (instruction.getPrevious() instanceof LabelNode) {
            return (LabelNode) instruction.getPrevious();
        }
        LabelNode label = new LabelNode();
        method.instructions.insertBefore(instruction, label);
        return label;
    }

    private static final class LocalRange {
        final String name;
        final String descriptor;
        final int slot;
        final AbstractInsnNode start;
        final AbstractInsnNode end;

        LocalRange(String name, String descriptor, int slot, AbstractInsnNode start, AbstractInsnNode end) {
            this.name = name;
            this.descriptor = descriptor;
            this.slot = slot;
            this.start = start;
            this.end = end;
        }
    }

    /** Tracks verifier-style initialization without loading any application dependencies. */
    private static final class UninitializedValue extends BasicValue {
        final Object allocation;

        UninitializedValue(Type type, Object allocation) {
            super(type);
            this.allocation = allocation;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof UninitializedValue
                    && allocation == ((UninitializedValue) other).allocation;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(allocation);
        }
    }

    private static final class DebugInterpreter extends BasicInterpreter {
        private final boolean constructor;
        private final Object uninitializedThis = new Object();

        DebugInterpreter(boolean constructor) {
            super(Opcodes.ASM9);
            this.constructor = constructor;
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type == null) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            return type == Type.VOID_TYPE ? null : new BasicValue(type);
        }

        @Override
        public BasicValue newParameterValue(boolean instance, int local, Type type) {
            return constructor && instance && local == 0
                    ? new UninitializedValue(type, uninitializedThis) : newValue(type);
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            if (instruction.getOpcode() == Opcodes.NEW) {
                return new UninitializedValue(Type.getObjectType(((TypeInsnNode) instruction).desc), instruction);
            }
            return super.newOperation(instruction);
        }

        @Override
        public BasicValue binaryOperation(AbstractInsnNode instruction, BasicValue first, BasicValue second)
                throws AnalyzerException {
            if (instruction.getOpcode() == Opcodes.AALOAD) {
                Type array = first.getType();
                return array != null && array.getSort() == Type.ARRAY
                        ? newValue(Type.getType(array.getDescriptor().substring(1))) : BasicValue.REFERENCE_VALUE;
            }
            return super.binaryOperation(instruction, first, second);
        }

        @Override
        public BasicValue merge(BasicValue first, BasicValue second) {
            if (first instanceof UninitializedValue || second instanceof UninitializedValue) {
                return first instanceof UninitializedValue && first.equals(second)
                        ? first : BasicValue.UNINITIALIZED_VALUE;
            }
            if (first.equals(second)) {
                return first;
            }
            if (first.isReference() && second.isReference()) {
                if (BasicInterpreter.NULL_TYPE.equals(first.getType())) {
                    return second;
                }
                if (BasicInterpreter.NULL_TYPE.equals(second.getType())) {
                    return first;
                }
                // Without a classpath, Object is a safe common reference type.
                return BasicValue.REFERENCE_VALUE;
            }
            if (isIntCategory(first.getType()) && isIntCategory(second.getType())) {
                return BasicValue.INT_VALUE;
            }
            return BasicValue.UNINITIALIZED_VALUE;
        }

        private boolean isIntCategory(Type type) {
            return type != null && type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.INT;
        }
    }

    private static final class DebugFrame extends Frame<BasicValue> {
        DebugFrame(int locals, int stack) {
            super(locals, stack);
        }

        DebugFrame(Frame<? extends BasicValue> frame) {
            super(frame);
        }

        @Override
        public void execute(AbstractInsnNode instruction, Interpreter<BasicValue> interpreter) throws AnalyzerException {
            UninitializedValue initialized = null;
            if (instruction.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(((MethodInsnNode) instruction).name)) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                int receiver = getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
                if (receiver >= 0 && getStack(receiver) instanceof UninitializedValue) {
                    initialized = (UninitializedValue) getStack(receiver);
                }
            }
            super.execute(instruction, interpreter);
            if (initialized != null) {
                BasicValue value = new BasicValue(initialized.getType());
                for (int i = 0; i < getLocals(); i++) {
                    if (initialized.equals(getLocal(i))) {
                        setLocal(i, value);
                    }
                }
                for (int i = 0; i < getStackSize(); i++) {
                    if (initialized.equals(getStack(i))) {
                        setStack(i, value);
                    }
                }
            }
        }
    }
}
