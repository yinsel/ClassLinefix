package com.killer.perfectlinerestorer.processor;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * CLASS File Processor
 * 
 * This class handles the processing of individual CLASS files,
 * applying line number restoration and writing the results.
 */
public class ClassProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ClassProcessor.class);
    
    private final LineNumberRestorer restorer;
    
    public ClassProcessor(LineNumberRestorer restorer) {
        this.restorer = restorer;
    }
    
    @Override
    public boolean processFile(Path inputFile, Path outputFile) throws IOException {
        return processClass(inputFile, outputFile);
    }
    
    @Override
    public boolean canProcess(Path filePath) {
        return filePath.toString().toLowerCase().endsWith(".class");
    }
    
    @Override
    public String getProcessorName() {
        return "CLASS Processor";
    }
    
    /**
     * Process a CLASS file
     * 
     * @param inputClass input CLASS file path
     * @param outputClass output CLASS file path
     * @return true if the file was modified, false otherwise
     * @throws IOException if an I/O error occurs
     */
    public boolean processClass(Path inputClass, Path outputClass) throws IOException {
        return processClass(inputClass, outputClass, null);
    }
    
    /**
     * Process a CLASS file with package filtering support
     * 
     * @param inputClass input CLASS file path
     * @param outputClass output CLASS file path
     * @param config command line configuration containing package filters
     * @return true if the file was modified, false otherwise
     * @throws IOException if an I/O error occurs
     */
    public boolean processClass(Path inputClass, Path outputClass, com.killer.perfectlinerestorer.Main.CommandLineConfig config) throws IOException {
        logger.debug("Processing CLASS: {} -> {}", inputClass, outputClass);
        
        try {
            // Read the original class file
            byte[] originalBytes = Files.readAllBytes(inputClass);
            
            // Check if this class should be excluded from processing
            if (config != null && shouldExcludeClass(originalBytes, config)) {
                logger.debug("Class {} does not pass package filters, copying as-is", inputClass.getFileName());
                
                // Ensure output directory exists
                Files.createDirectories(outputClass.getParent());
                
                // Copy the file as-is if it's in an excluded package
                Files.copy(inputClass, outputClass, StandardCopyOption.REPLACE_EXISTING, 
                          StandardCopyOption.COPY_ATTRIBUTES);
                return false; // Not modified
            }
            
            // Check if the class already has line numbers
            if (!restorer.isDebugInfoEnabled() && restorer.hasLineNumbers(originalBytes)) {
                logger.debug("Class {} already has line numbers, copying as-is", inputClass.getFileName());
                
                // Ensure output directory exists
                Files.createDirectories(outputClass.getParent());
                
                // Copy the file as-is if it already has line numbers
                Files.copy(inputClass, outputClass, StandardCopyOption.REPLACE_EXISTING, 
                          StandardCopyOption.COPY_ATTRIBUTES);
                return false; // Not modified
            }
            
            // Apply line number restoration
            byte[] processedBytes = restorer.restoreLineNumbers(originalBytes);
            
            // Check if the file was actually modified
            boolean modified = !java.util.Arrays.equals(originalBytes, processedBytes);
            
            if (modified) {
                // Ensure output directory exists
                Files.createDirectories(outputClass.getParent());
                
                // Write the processed class file
                Files.write(outputClass, processedBytes);
                
                // Copy file attributes from original
                try {
                    Files.setLastModifiedTime(outputClass, Files.getLastModifiedTime(inputClass));
                } catch (Exception e) {
                    logger.debug("Failed to copy file attributes: {}", e.getMessage());
                }
                
                logger.debug("CLASS file processed and modified: {}", inputClass.getFileName());
            } else {
                // Ensure output directory exists
                Files.createDirectories(outputClass.getParent());
                
                // Copy the original file if no modifications were made
                Files.copy(inputClass, outputClass, StandardCopyOption.REPLACE_EXISTING, 
                          StandardCopyOption.COPY_ATTRIBUTES);
                logger.debug("CLASS file processed but not modified: {}", inputClass.getFileName());
            }
            
            return modified;
            
        } catch (Exception e) {
            logger.error("Error processing CLASS file {}: {}", inputClass, e.getMessage(), e);
            
            // On error, copy the original file
            try {
                // Ensure output directory exists
                Files.createDirectories(outputClass.getParent());
                
                Files.copy(inputClass, outputClass, StandardCopyOption.REPLACE_EXISTING, 
                          StandardCopyOption.COPY_ATTRIBUTES);
                logger.warn("Copied original file due to processing error: {}", inputClass.getFileName());
            } catch (IOException copyError) {
                logger.error("Failed to copy original file: {}", copyError.getMessage());
                throw copyError;
            }
            
            return false;
        }
    }
    
    /** Check package filters without creating a backup or output file. */
    public boolean passesPackageFilters(Path inputClass,
            com.killer.perfectlinerestorer.Main.CommandLineConfig config) throws IOException {
        return config == null || !shouldExcludeClass(Files.readAllBytes(inputClass), config);
    }

    /**
     * Check if a class should be excluded from line number processing
     * based on its package name
     */
    private boolean shouldExcludeClass(byte[] classBytes, com.killer.perfectlinerestorer.Main.CommandLineConfig config) {
        try {
            ClassReader classReader = new ClassReader(classBytes);
            String className = classReader.getClassName();
            return !config.shouldProcessClass(className);
        } catch (Exception e) {
            logger.debug("Failed to read class name for package filtering, copying as-is: {}", e.getMessage());
            return true; // An unreadable class cannot be matched safely
        }
    }
    
    /**
     * Validate CLASS file integrity
     */
    public boolean validateClass(Path classFile) {
        try {
            byte[] classBytes = Files.readAllBytes(classFile);
            
            // Basic validation - check magic number
            if (classBytes.length < 4) {
                return false;
            }
            
            // Java class files start with 0xCAFEBABE
            int magic = ((classBytes[0] & 0xFF) << 24) |
                       ((classBytes[1] & 0xFF) << 16) |
                       ((classBytes[2] & 0xFF) << 8) |
                       (classBytes[3] & 0xFF);
            
            if (magic != 0xCAFEBABE) {
                logger.warn("Invalid class file magic number: {}", classFile);
                return false;
            }
            
            // Try to parse with ASM for more thorough validation
            restorer.hasLineNumbers(classBytes);
            
            return true;
            
        } catch (Exception e) {
            logger.error("CLASS validation failed for {}: {}", classFile, e.getMessage());
            return false;
        }
    }
    
    /**
     * Get CLASS file statistics
     */
    public ClassStats getClassStats(Path classFile) throws IOException {
        ClassStats stats = new ClassStats();
        
        try {
            byte[] classBytes = Files.readAllBytes(classFile);
            stats.fileSize = classBytes.length;
            stats.hasLineNumbers = restorer.hasLineNumbers(classBytes);
            
            // Basic class file info
            if (classBytes.length >= 8) {
                stats.minorVersion = ((classBytes[4] & 0xFF) << 8) | (classBytes[5] & 0xFF);
                stats.majorVersion = ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
            }
            
            stats.valid = validateClass(classFile);
            
        } catch (Exception e) {
            logger.error("Failed to get stats for {}: {}", classFile, e.getMessage());
            stats.valid = false;
        }
        
        return stats;
    }
    
    /**
     * Check if a file is a valid Java class file
     */
    public static boolean isValidClassFile(Path filePath) {
        try {
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                return false;
            }
            
            byte[] header = new byte[4];
            try (java.io.InputStream inputStream = Files.newInputStream(filePath)) {
                int bytesRead = inputStream.read(header);
                if (bytesRead < 4) {
                    return false;
                }
            }
            
            // Check magic number
            int magic = ((header[0] & 0xFF) << 24) |
                       ((header[1] & 0xFF) << 16) |
                       ((header[2] & 0xFF) << 8) |
                       (header[3] & 0xFF);
            
            return magic == 0xCAFEBABE;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * CLASS file statistics
     */
    public static class ClassStats {
        public long fileSize = 0;
        public boolean hasLineNumbers = false;
        public boolean valid = false;
        public int majorVersion = 0;
        public int minorVersion = 0;
        
        public String getJavaVersion() {
            switch (majorVersion) {
                case 45: return "Java 1.1";
                case 46: return "Java 1.2";
                case 47: return "Java 1.3";
                case 48: return "Java 1.4";
                case 49: return "Java 5";
                case 50: return "Java 6";
                case 51: return "Java 7";
                case 52: return "Java 8";
                case 53: return "Java 9";
                case 54: return "Java 10";
                case 55: return "Java 11";
                case 56: return "Java 12";
                case 57: return "Java 13";
                case 58: return "Java 14";
                case 59: return "Java 15";
                case 60: return "Java 16";
                case 61: return "Java 17";
                case 62: return "Java 18";
                case 63: return "Java 19";
                case 64: return "Java 20";
                case 65: return "Java 21";
                default: return "Unknown (" + majorVersion + ")";
            }
        }
        
        @Override
        public String toString() {
            return String.format("ClassStats{size=%d bytes, hasLineNumbers=%s, valid=%s, version=%s}", 
                               fileSize, hasLineNumbers, valid, getJavaVersion());
        }
    }
}
