package com.killer.perfectlinerestorer.core;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Core Line Number Restoration Engine
 * 
 * This class implements the main logic for restoring line numbers in Java bytecode,
 * using multiple advanced restoration strategies.
 */
public class LineNumberRestorer {
    private static final Logger logger = LoggerFactory.getLogger(LineNumberRestorer.class);
    
    private RestoreStrategy defaultStrategy = RestoreStrategy.HYBRID;
    private boolean debugInfo = false;
    private boolean rebuildLines = false;
    private boolean skipInnerClasses = false; // Default value
    
    /**
     * Default constructor
     */
    public LineNumberRestorer() {
        // Use default values
    }
    
    /**
     * Constructor with configuration
     */
    public LineNumberRestorer(com.killer.perfectlinerestorer.Main.CommandLineConfig config) {
        if (config != null) {
            this.skipInnerClasses = config.isSkipInnerClasses();
            this.debugInfo = config.isDebugInfo();
            this.rebuildLines = config.isRebuildLines();
        }
    }
    
    public boolean isDebugInfoEnabled() {
        return debugInfo;
    }

    /**
     * Check if a class already has line number information
     */
    public boolean hasLineNumbers(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            
            return hasLineNumbers(classNode);
        } catch (Exception e) {
            logger.warn("Error checking line numbers: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a ClassNode already has line number information
     */
    public boolean hasLineNumbers(ClassNode classNode) {
        if (classNode.methods == null) {
            return false;
        }
        
        for (MethodNode method : classNode.methods) {
            if (method.instructions != null) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LineNumberNode) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Restore line numbers in bytecode using the default strategy
     */
    public byte[] restoreLineNumbers(byte[] classBytes) {
        return restoreLineNumbers(classBytes, defaultStrategy);
    }
    
    /**
     * Restore line numbers in bytecode using specified strategy
     */
    public byte[] restoreLineNumbers(byte[] classBytes, RestoreStrategy strategy) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            
            // Skip enum classes
            if (!debugInfo && (classNode.access & Opcodes.ACC_ENUM) != 0) {
                logger.debug("Class {} is an enum, skipping line number restoration", classNode.name);
                return classBytes;
            }
            
            // Skip inner classes (if enabled)
            if (skipInnerClasses && isInnerClass(classNode.name)) {
                logger.debug("Class {} is an inner class, skipping line number restoration", classNode.name);
                return classBytes;
            }
            
            // Skip classes that contain inner classes (if enabled)
            if (skipInnerClasses && hasInnerClasses(classNode)) {
                logger.debug("Class {} contains inner classes, skipping line number restoration", classNode.name);
                return classBytes;
            }
            
            // Detailed metadata repair must also run on classes that already have line numbers.
            if (debugInfo) {
                if (rebuildLines) {
                    for (MethodNode method : classNode.methods) {
                        for (AbstractInsnNode instruction : method.instructions.toArray()) {
                            if (instruction instanceof LineNumberNode) method.instructions.remove(instruction);
                        }
                    }
                    classNode.sourceDebug = null;
                }
                if (!new DebugInfoRestorer().restore(classNode) && !rebuildLines) {
                    return classBytes;
                }
                ClassWriter writer = new ClassWriter(0);
                classNode.accept(writer);
                return writer.toByteArray();
            }

            // Skip if already has line numbers
            if (hasLineNumbers(classNode)) {
                logger.debug("Class {} already has line numbers, skipping", classNode.name);
                return classBytes;
            }
            
            // Apply line number restoration strategy
            boolean modified = applyStrategy(classNode, strategy);
            
            if (!modified) {
                return classBytes;
            }
            
            // Write modified class
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            
            logger.debug("Applied {} strategy to class {}", strategy.getDisplayName(), classNode.name);
            return writer.toByteArray();
            
        } catch (Exception e) {
            logger.error("Error restoring line numbers: {}", e.getMessage(), e);
            return classBytes;
        }
    }
    
    /**
     * Apply the specified restoration strategy
     */
    private boolean applyStrategy(ClassNode classNode, RestoreStrategy strategy) {
        switch (strategy) {
            case SEQUENTIAL:
                return applySequentialStrategy(classNode);
            case EXCEPTION_ORIENTED:
                return applyExceptionOrientedStrategy(classNode);
            case INTELLIGENT:
                return applyIntelligentStrategy(classNode);
            case HYBRID:
                return applyHybridStrategy(classNode);
            case AUTO:
                return applyAutoStrategy(classNode);
            default:
                logger.warn("Unknown strategy: {}, falling back to HYBRID", strategy);
                return applyHybridStrategy(classNode);
        }
    }
    
    /**
     * Sequential strategy - assigns line numbers to each instruction
     * (Classic line number restoration approach)
     */
    private boolean applySequentialStrategy(ClassNode classNode) {
        boolean modified = false;
        int currentLine = 1;
        
        // Ensure source file is set
        if (classNode.sourceFile == null) {
            classNode.sourceFile = getSourceFileName(classNode.name);
        }
        
        if (classNode.methods != null) {
            for (MethodNode method : classNode.methods) {
                if (method.instructions != null && method.instructions.size() > 0) {
                    // Skip empty methods or simple return methods (including <clinit>)
                    if (isEmptyOrSimpleReturnMethod(method)) {
                        logger.debug("Skipping empty or simple return method: {}", method.name);
                        continue;
                    }
                    
                    // Skip inner class methods
                    if (isInnerClassMethod(method)) {
                        logger.debug("Skipping inner class method: {}", method.name);
                        continue;
                    }
                    
                    // Remove existing line number nodes
                    removeExistingLineNumbers(method);
                    
                    // Add line numbers to each instruction using safe label handling
                    int linesAdded = addSequentialLineNumbers(method, currentLine);
                    if (linesAdded > 0) {
                        currentLine += linesAdded;
                        modified = true;
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Exception-oriented strategy - adds line numbers only to exception-prone instructions
     * (Lightweight restoration approach)
     */
    private boolean applyExceptionOrientedStrategy(ClassNode classNode) {
        boolean modified = false;
        
        // Ensure source file is set
        if (classNode.sourceFile == null) {
            classNode.sourceFile = getSourceFileName(classNode.name);
        }
        
        if (classNode.methods != null) {
            int currentLine = 1;
            for (MethodNode method : classNode.methods) {
                if (method.instructions != null && method.instructions.size() > 0) {
                    // Skip empty methods or simple return methods (including <clinit>)
                    if (isEmptyOrSimpleReturnMethod(method)) {
                        logger.debug("Skipping empty or simple return method: {}", method.name);
                        continue;
                    }
                    
                    // Skip inner class methods
                    if (isInnerClassMethod(method)) {
                        logger.debug("Skipping inner class method: {}", method.name);
                        continue;
                    }
                    
                    // Remove existing line number nodes
                    removeExistingLineNumbers(method);
                    
                    // Add line numbers using safe label handling
                    int linesAdded = addExceptionOrientedLineNumbers(method, currentLine);
                    if (linesAdded > 0) {
                        currentLine += linesAdded;
                        modified = true;
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Intelligent strategy - analyzes bytecode patterns for semantic placement
     * (Advanced semantic analysis approach)
     */
    private boolean applyIntelligentStrategy(ClassNode classNode) {
        boolean modified = false;
        
        // Ensure source file is set
        if (classNode.sourceFile == null) {
            classNode.sourceFile = getSourceFileName(classNode.name);
        }
        
        if (classNode.methods != null) {
            int currentLine = 1;
            
            for (MethodNode method : classNode.methods) {
                if (method.instructions != null && method.instructions.size() > 0) {
                    // Skip empty methods or simple return methods (including <clinit>)
                    if (isEmptyOrSimpleReturnMethod(method)) {
                        logger.debug("Skipping empty or simple return method: {}", method.name);
                        continue;
                    }
                    
                    // Skip inner class methods
                    if (isInnerClassMethod(method)) {
                        logger.debug("Skipping inner class method: {}", method.name);
                        continue;
                    }
                    
                    // Remove existing line number nodes
                    removeExistingLineNumbers(method);
                    
                    // Analyze method structure and add intelligent line numbers
                    int linesAdded = addIntelligentLineNumbers(method, currentLine);
                    if (linesAdded > 0) {
                        currentLine += linesAdded;
                        modified = true;
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Hybrid strategy - combines all approaches for optimal results
     */
    private boolean applyHybridStrategy(ClassNode classNode) {
        boolean modified = false;
        
        // Ensure source file is set
        if (classNode.sourceFile == null) {
            classNode.sourceFile = getSourceFileName(classNode.name);
        }
        
        if (classNode.methods != null) {
            int currentLine = 1;
            
            for (MethodNode method : classNode.methods) {
                if (method.instructions != null && method.instructions.size() > 0) {
                    // Skip empty methods or simple return methods (including <clinit>)
                    if (isEmptyOrSimpleReturnMethod(method)) {
                        logger.debug("Skipping empty or simple return method: {}", method.name);
                        continue;
                    }
                    
                    // Skip inner class methods
                    if (isInnerClassMethod(method)) {
                        logger.debug("Skipping inner class method: {}", method.name);
                        continue;
                    }
                    
                    // Remove existing line number nodes
                    removeExistingLineNumbers(method);
                    
                    // Use different strategies based on method characteristics
                    if (isComplexMethod(method)) {
                        // Use intelligent strategy for complex methods
                        int linesAdded = addIntelligentLineNumbers(method, currentLine);
                        if (linesAdded > 0) {
                            currentLine += linesAdded;
                            modified = true;
                        }
                    } else if (hasExceptionProneInstructions(method)) {
                        // Use exception-oriented strategy for methods with exceptions
                        int linesAdded = addExceptionOrientedLineNumbers(method, currentLine);
                        if (linesAdded > 0) {
                            currentLine += linesAdded;
                            modified = true;
                        }
                    } else {
                        // Use sequential strategy for simple methods
                        int linesAdded = addSequentialLineNumbers(method, currentLine);
                        if (linesAdded > 0) {
                            currentLine += linesAdded;
                            modified = true;
                        }
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Auto strategy - automatically selects the best strategy
     */
    private boolean applyAutoStrategy(ClassNode classNode) {
        // Analyze class characteristics and choose the best strategy
        RestoreStrategy selectedStrategy = analyzeAndSelectStrategy(classNode);
        logger.debug("Auto-selected strategy {} for class {}", selectedStrategy.getDisplayName(), classNode.name);
        return applyStrategy(classNode, selectedStrategy);
    }
    
    /**
     * Analyze class and select the most appropriate strategy
     */
    private RestoreStrategy analyzeAndSelectStrategy(ClassNode classNode) {
        if (classNode.methods == null || classNode.methods.isEmpty()) {
            return RestoreStrategy.SEQUENTIAL;
        }
        
        int totalInstructions = 0;
        int exceptionProneInstructions = 0;
        int complexMethods = 0;
        
        for (MethodNode method : classNode.methods) {
            if (method.instructions != null) {
                // Skip inner class methods in analysis
                if (isInnerClassMethod(method)) {
                    continue;
                }
                
                int methodInstructions = method.instructions.size();
                totalInstructions += methodInstructions;
                
                if (isComplexMethod(method)) {
                    complexMethods++;
                }
                
                for (AbstractInsnNode insn : method.instructions) {
                    if (couldThrowException(insn)) {
                        exceptionProneInstructions++;
                    }
                }
            }
        }
        
        // Decision logic
        double exceptionRatio = totalInstructions > 0 ? (double) exceptionProneInstructions / totalInstructions : 0;
        double complexMethodRatio = classNode.methods.size() > 0 ? (double) complexMethods / classNode.methods.size() : 0;
        
        if (complexMethodRatio > 0.5) {
            return RestoreStrategy.INTELLIGENT;
        } else if (exceptionRatio > 0.3) {
            return RestoreStrategy.EXCEPTION_ORIENTED;
        } else if (totalInstructions > 1000) {
            return RestoreStrategy.HYBRID;
        } else {
            return RestoreStrategy.SEQUENTIAL;
        }
    }
    
    // Helper methods
    
    private void removeExistingLineNumbers(MethodNode method) {
        Iterator<AbstractInsnNode> iterator = method.instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn instanceof LineNumberNode) {
                iterator.remove();
            }
        }
    }
    
    private boolean couldThrowException(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            return true;
        }
        
        int opcode = insn.getOpcode();
        return opcode == Opcodes.AALOAD || opcode == Opcodes.AASTORE ||
               opcode == Opcodes.BALOAD || opcode == Opcodes.BASTORE ||
               opcode == Opcodes.CALOAD || opcode == Opcodes.CASTORE ||
               opcode == Opcodes.DALOAD || opcode == Opcodes.DASTORE ||
               opcode == Opcodes.FALOAD || opcode == Opcodes.FASTORE ||
               opcode == Opcodes.IALOAD || opcode == Opcodes.IASTORE ||
               opcode == Opcodes.LALOAD || opcode == Opcodes.LASTORE ||
               opcode == Opcodes.SALOAD || opcode == Opcodes.SASTORE ||
               opcode == Opcodes.ATHROW || opcode == Opcodes.CHECKCAST ||
               opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD ||
               opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC ||
               opcode == Opcodes.IDIV || opcode == Opcodes.LDIV ||
               opcode == Opcodes.IREM || opcode == Opcodes.LREM ||
               opcode == Opcodes.ANEWARRAY || opcode == Opcodes.NEWARRAY ||
               opcode == Opcodes.MULTIANEWARRAY || opcode == Opcodes.NEW;
    }
    
    private boolean isComplexMethod(MethodNode method) {
        if (method.instructions == null) {
            return false;
        }
        
        int instructionCount = method.instructions.size();
        int jumpCount = 0;
        int switchCount = 0;
        
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode) {
                jumpCount++;
            } else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                switchCount++;
            }
        }
        
        return instructionCount > 50 || jumpCount > 5 || switchCount > 0;
    }
    
    private boolean hasExceptionProneInstructions(MethodNode method) {
        if (method.instructions == null) {
            return false;
        }
        
        for (AbstractInsnNode insn : method.instructions) {
            if (couldThrowException(insn)) {
                return true;
            }
        }
        
        return false;
    }
    
    private int addIntelligentLineNumbers(MethodNode method, int startLine) {
        // Skip empty methods or methods with only RETURN instruction
        if (isEmptyOrSimpleReturnMethod(method)) {
            logger.debug("Skipping empty or simple return method: {}", method.name);
            return 0;
        }
        
        // Simplified intelligent line number placement
        int linesAdded = 0;
        int currentLine = startLine;
        
        for (AbstractInsnNode insn : method.instructions) {
            if (shouldAddLineNumber(insn)) {
                // Check if there's already a label before this instruction
                LabelNode label = findOrCreateLabelBefore(method, insn);
                LineNumberNode lineNumber = new LineNumberNode(currentLine++, label);
                
                // Only insert the line number, not a new label
                method.instructions.insertBefore(insn, lineNumber);
                linesAdded++;
            }
        }
        
        return linesAdded;
    }
    
    private int addExceptionOrientedLineNumbers(MethodNode method, int startLine) {
        // Skip empty methods or methods with only RETURN instruction
        if (isEmptyOrSimpleReturnMethod(method)) {
            logger.debug("Skipping empty or simple return method: {}", method.name);
            return 0;
        }
        
        int linesAdded = 0;
        int currentLine = startLine;
        
        for (AbstractInsnNode insn : method.instructions) {
            if (couldThrowException(insn)) {
                // Check if there's already a label before this instruction
                LabelNode label = findOrCreateLabelBefore(method, insn);
                LineNumberNode lineNumber = new LineNumberNode(currentLine++, label);
                
                // Only insert the line number, not a new label
                method.instructions.insertBefore(insn, lineNumber);
                linesAdded++;
            }
        }
        
        return linesAdded;
    }
    
    private int addSequentialLineNumbers(MethodNode method, int startLine) {
        int linesAdded = 0;
        int currentLine = startLine;
        
        // Skip empty methods or methods with only RETURN instruction
        if (isEmptyOrSimpleReturnMethod(method)) {
            logger.debug("Skipping empty or simple return method: {}", method.name);
            return 0;
        }
        
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && 
                !(insn instanceof FrameNode)) {
                
                // Check if there's already a label before this instruction
                LabelNode label = findOrCreateLabelBefore(method, insn);
                LineNumberNode lineNumber = new LineNumberNode(currentLine++, label);
                
                // Only insert the line number, not a new label
                method.instructions.insertBefore(insn, lineNumber);
                linesAdded++;
            }
        }
        
        return linesAdded;
    }
    
    private boolean shouldAddLineNumber(AbstractInsnNode insn) {
        // Add line numbers to significant instructions
        return !(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && 
               !(insn instanceof FrameNode) && 
               (couldThrowException(insn) || insn instanceof JumpInsnNode || 
                insn instanceof VarInsnNode || insn instanceof FieldInsnNode);
    }
    
    /**
     * Find an existing label before the instruction or create a new one if needed.
     * This prevents breaking existing jump targets.
     */
    private LabelNode findOrCreateLabelBefore(MethodNode method, AbstractInsnNode insn) {
        // Check if the previous instruction is already a label
        AbstractInsnNode prev = insn.getPrevious();
        if (prev instanceof LabelNode) {
            return (LabelNode) prev;
        }
        
        // Check if this instruction is directly referenced by any jump
        LabelNode existingLabel = findDirectJumpTargetLabel(method, insn);
        if (existingLabel != null) {
            return existingLabel;
        }
        
        // Create a new label only if we don't break existing jump targets
        LabelNode newLabel = new LabelNode();
        method.instructions.insertBefore(insn, newLabel);
        return newLabel;
    }
    
    /**
     * Check if this instruction is directly referenced by any jump instruction.
     * If so, find the corresponding label that should be preserved.
     */
    private LabelNode findDirectJumpTargetLabel(MethodNode method, AbstractInsnNode targetInsn) {
        // First, find all labels that point to this instruction
        Set<LabelNode> candidateLabels = new HashSet<>();
        
        // Look for labels immediately before the target instruction
        AbstractInsnNode current = targetInsn.getPrevious();
        while (current != null && (current instanceof LabelNode || current instanceof LineNumberNode || current instanceof FrameNode)) {
            if (current instanceof LabelNode) {
                candidateLabels.add((LabelNode) current);
            }
            current = current.getPrevious();
        }
        
        // Now check if any of these labels are referenced by jump instructions
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode) {
                JumpInsnNode jumpInsn = (JumpInsnNode) insn;
                if (candidateLabels.contains(jumpInsn.label)) {
                    return jumpInsn.label;
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode switchInsn = (TableSwitchInsnNode) insn;
                if (candidateLabels.contains(switchInsn.dflt)) {
                    return switchInsn.dflt;
                }
                for (LabelNode label : switchInsn.labels) {
                    if (candidateLabels.contains(label)) {
                        return label;
                    }
                }
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode switchInsn = (LookupSwitchInsnNode) insn;
                if (candidateLabels.contains(switchInsn.dflt)) {
                    return switchInsn.dflt;
                }
                for (LabelNode label : switchInsn.labels) {
                    if (candidateLabels.contains(label)) {
                        return label;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if the target instruction comes immediately after the given label.
     */
    private boolean isInstructionAfterLabel(LabelNode label, AbstractInsnNode targetInsn) {
        AbstractInsnNode current = label.getNext();
        // Skip line numbers and frames to find the actual instruction
        while (current != null && (current instanceof LineNumberNode || current instanceof FrameNode)) {
            current = current.getNext();
        }
        return current == targetInsn;
    }
    
    private int estimateMethodLines(MethodNode method) {
        if (method.instructions == null) {
            return 10;
        }
        
        // Rough estimation based on instruction count
        return Math.max(10, method.instructions.size() / 3);
    }
    
    private boolean isEmptyOrSimpleReturnMethod(MethodNode method) {
        // Always skip static initializer blocks
        if (method.name.equals("<clinit>")) {
            return true;
        }
        
        if (method.instructions == null || method.instructions.size() == 0) {
            return true;
        }
        
        // Count actual instructions (excluding labels, line numbers, frames)
        int actualInstructionCount = 0;
        boolean hasOnlyReturn = true;
        
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && 
                !(insn instanceof FrameNode)) {
                actualInstructionCount++;
                
                // Check if it's not a simple return instruction
                int opcode = insn.getOpcode();
                if (opcode != Opcodes.RETURN && opcode != Opcodes.ARETURN && 
                    opcode != Opcodes.IRETURN && opcode != Opcodes.LRETURN && 
                    opcode != Opcodes.FRETURN && opcode != Opcodes.DRETURN) {
                    hasOnlyReturn = false;
                }
            }
        }
        
        // Consider it empty/simple if:
        // 1. No actual instructions
        // 2. Only one instruction and it's a return
        // 3. Only constructor call + return (for constructors)
        if (actualInstructionCount == 0) {
            return true;
        }
        
        if (actualInstructionCount == 1 && hasOnlyReturn) {
            return true;
        }
        
        // For constructors, allow ALOAD_0 + INVOKESPECIAL + RETURN pattern
        if (method.name.equals("<init>") && actualInstructionCount <= 3) {
            return isSimpleConstructor(method);
        }
        
        return false;
    }
    
    private boolean isSimpleConstructor(MethodNode method) {
        // Check for simple constructor pattern: ALOAD_0, INVOKESPECIAL Object.<init>, RETURN
        int instructionIndex = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && 
                !(insn instanceof FrameNode)) {
                
                switch (instructionIndex) {
                    case 0:
                        if (insn.getOpcode() != Opcodes.ALOAD) return false;
                        break;
                    case 1:
                        if (!(insn instanceof MethodInsnNode) || 
                            insn.getOpcode() != Opcodes.INVOKESPECIAL) return false;
                        break;
                    case 2:
                        if (insn.getOpcode() != Opcodes.RETURN) return false;
                        break;
                    default:
                        return false; // More than 3 instructions
                }
                instructionIndex++;
            }
        }
        
        return instructionIndex == 3;
    }
    
    /**
     * Check if a class is an inner class based on its name
     * Inner classes contain '$' in their name (e.g., OuterClass$InnerClass)
     */
    private boolean isInnerClass(String className) {
        return className != null && className.contains("$");
    }
    
    /**
     * Check if a method belongs to an inner class or contains inner class references
     * @param method the method to check
     * @return true if the method should be skipped due to inner class association
     */
    private boolean isInnerClassMethod(MethodNode method) {
        if (method == null) {
            return false;
        }
        
        // Check if method name contains inner class indicators
        if (method.name != null && method.name.contains("$")) {
            return true;
        }
        
        // Check method descriptor for inner class references
        if (method.desc != null && method.desc.contains("$")) {
            return true;
        }
        
        // Check method signature for inner class references
        if (method.signature != null && method.signature.contains("$")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if a method uses inner classes by analyzing its bytecode instructions
     * @param method the method to check
     * @return true if the method uses inner classes, false otherwise
     */
    private boolean methodUsesInnerClasses(MethodNode method) {
        if (method == null || method.instructions == null) {
            return false;
        }
        
        logger.debug("Checking method {} for inner class usage", method.name);
        
        // Analyze method instructions for inner class usage
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode) {
                TypeInsnNode typeInsn = (TypeInsnNode) insn;
                if (typeInsn.desc != null && typeInsn.desc.contains("$")) {
                    logger.debug("Method {} uses inner class in type instruction: {}", method.name, typeInsn.desc);
                    return true;
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                if (fieldInsn.owner != null && fieldInsn.owner.contains("$")) {
                    logger.debug("Method {} uses inner class in field instruction: {}", method.name, fieldInsn.owner);
                    return true;
                }
                if (fieldInsn.desc != null && fieldInsn.desc.contains("$")) {
                    logger.debug("Method {} uses inner class in field descriptor: {}", method.name, fieldInsn.desc);
                    return true;
                }
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if (methodInsn.owner != null && methodInsn.owner.contains("$")) {
                    logger.debug("Method {} uses inner class in method instruction: {}", method.name, methodInsn.owner);
                    return true;
                }
                if (methodInsn.desc != null && methodInsn.desc.contains("$")) {
                    logger.debug("Method {} uses inner class in method descriptor: {}", method.name, methodInsn.desc);
                    return true;
                }
            } else if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldcInsn = (LdcInsnNode) insn;
                if (ldcInsn.cst instanceof Type) {
                    Type type = (Type) ldcInsn.cst;
                    if (type.getDescriptor().contains("$")) {
                        logger.debug("Method {} uses inner class in LDC instruction: {}", method.name, type.getDescriptor());
                        return true;
                    }
                }
            }
        }
        
        logger.debug("Method {} does not use inner classes", method.name);
        return false;
    }
    
    /**
     * Check if a class contains inner classes
     * @param classNode the class node to check
     * @return true if the class contains inner classes, false otherwise
     */
    private boolean hasInnerClasses(ClassNode classNode) {
        return classNode.innerClasses != null && !classNode.innerClasses.isEmpty();
    }
    
    private String getSourceFileName(String className) {
        int lastSlash = className.lastIndexOf('/');
        String simpleName = lastSlash >= 0 ? className.substring(lastSlash + 1) : className;
        int dollarIndex = simpleName.indexOf('$');
        if (dollarIndex >= 0) {
            simpleName = simpleName.substring(0, dollarIndex);
        }
        return simpleName + ".java";
    }
    
    // Getters and setters
    
    public RestoreStrategy getDefaultStrategy() {
        return defaultStrategy;
    }
    
    public void setDefaultStrategy(RestoreStrategy defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }
}