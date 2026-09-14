package com.killer.perfectlinerestorer.processor;

import com.killer.perfectlinerestorer.core.LineNumberRestorer;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * JAR File Processor
 * 
 * This class handles the processing of JAR files, extracting CLASS files,
 * applying line number restoration, and repackaging them.
 */
public class JarProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(JarProcessor.class);
    
    private final LineNumberRestorer restorer;
    
    public JarProcessor(LineNumberRestorer restorer) {
        this.restorer = restorer;
    }
    
    @Override
    public boolean processFile(Path inputFile, Path outputFile) throws IOException {
        return processJar(inputFile, outputFile);
    }
    
    @Override
    public boolean canProcess(Path filePath) {
        return filePath.toString().toLowerCase().endsWith(".jar");
    }
    
    @Override
    public String getProcessorName() {
        return "JAR Processor";
    }
    
    /**
     * Process a JAR file
     * 
     * @param inputJar input JAR file path
     * @param outputJar output JAR file path
     * @return true if any modifications were made, false otherwise
     * @throws IOException if an I/O error occurs
     */
    public boolean processJar(Path inputJar, Path outputJar) throws IOException {
        return processJar(inputJar, outputJar, null);
    }
    
    /**
     * Process a JAR file with package filtering support
     * 
     * @param inputJar input JAR file path
     * @param outputJar output JAR file path
     * @param config command line configuration containing package filters
     * @return true if any modifications were made, false otherwise
     * @throws IOException if an I/O error occurs
     */
    public boolean processJar(Path inputJar, Path outputJar, com.killer.perfectlinerestorer.Main.CommandLineConfig config) throws IOException {
        logger.debug("Processing JAR: {} -> {}", inputJar, outputJar);
        
        // Ensure output directory exists
        Files.createDirectories(outputJar.getParent());
        
        // First try with JarInputStream for proper manifest handling
        try {
            return processJarWithJarStream(inputJar, outputJar, config);
        } catch (SecurityException e) {
            // If digest verification fails, fall back to ZIP-based processing
            logger.warn("Digest verification failed for {}, falling back to ZIP processing: {}", inputJar.getFileName(), e.getMessage());
            return processJarWithZipStream(inputJar, outputJar, config);
        }
    }
    
    /**
     * Process JAR using JarInputStream (supports manifest handling but enforces digest verification)
     */
    private boolean processJarWithJarStream(Path inputJar, Path outputJar, com.killer.perfectlinerestorer.Main.CommandLineConfig config) throws IOException {
        boolean anyModified = false;
        
        try (JarInputStream jarInput = new JarInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
             JarOutputStream jarOutput = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {
            
            // Copy manifest if present, but clean MD5 and SHA-1 digests
            Manifest manifest = jarInput.getManifest();
            if (manifest != null) {
                cleanManifestDigests(manifest);
                jarOutput.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
                manifest.write(jarOutput);
                jarOutput.closeEntry();
            }
            
            JarEntry entry;
            byte[] buffer = new byte[8192];
            
            while ((entry = jarInput.getNextJarEntry()) != null) {
                String entryName = entry.getName();
                
                // Skip manifest (already handled)
                if (JarFile.MANIFEST_NAME.equals(entryName)) {
                    continue;
                }
                
                // Read entry content
                ByteArrayOutputStream entryContent = new ByteArrayOutputStream();
                int bytesRead;
                while ((bytesRead = jarInput.read(buffer)) != -1) {
                    entryContent.write(buffer, 0, bytesRead);
                }
                byte[] entryBytes = entryContent.toByteArray();
                
                // Skip signature files to avoid MD5 digest errors
                if (isSignatureFile(entryName)) {
                    logger.debug("Skipping signature file: {}", entryName);
                    continue;
                }
                
                // Process CLASS files
                if (entryName.endsWith(".class") && !entryName.contains("module-info")) {
                    try {
                        // Check if this class should be excluded from processing
                        boolean shouldExclude = config != null && shouldExcludeClass(entryBytes, config);
                        
                        byte[] processedBytes;
                        boolean modified = false;
                        
                        if (shouldExclude) {
                            logger.debug("Class {} does not pass package filters, copying as-is", entryName);
                            processedBytes = entryBytes; // Use original bytes
                        } else {
                            processedBytes = restorer.restoreLineNumbers(entryBytes);
                            modified = !java.util.Arrays.equals(entryBytes, processedBytes);
                        }
                        
                        if (modified) {
                            anyModified = true;
                            logger.debug("Modified class in JAR: {}", entryName);
                        }
                        
                        // Create new entry with processed content
                        JarEntry newEntry = new JarEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        jarOutput.putNextEntry(newEntry);
                        jarOutput.write(processedBytes);
                        
                    } catch (Exception e) {
                        logger.warn("Failed to process class {} in JAR {}: {}", entryName, inputJar.getFileName(), e.getMessage());
                        
                        // Write original content on error
                        JarEntry newEntry = new JarEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        jarOutput.putNextEntry(newEntry);
                        jarOutput.write(entryBytes);
                    }
                } else {
                    // Copy non-CLASS files as-is
                    JarEntry newEntry = new JarEntry(entryName);
                    newEntry.setTime(entry.getTime());
                    
                    // Preserve directory entries
                    if (entry.isDirectory()) {
                        jarOutput.putNextEntry(newEntry);
                    } else {
                        jarOutput.putNextEntry(newEntry);
                        jarOutput.write(entryBytes);
                    }
                }
                
                jarOutput.closeEntry();
            }
        }
        
        logger.debug("JAR processing completed: {} (modified: {})", inputJar.getFileName(), anyModified);
        return anyModified;
    }
    
    /**
     * Process JAR using ZipInputStream (bypasses digest verification but loses manifest handling)
     */
    private boolean processJarWithZipStream(Path inputJar, Path outputJar, com.killer.perfectlinerestorer.Main.CommandLineConfig config) throws IOException {
        logger.debug("Processing JAR with ZIP streams: {} -> {}", inputJar, outputJar);
        
        boolean anyModified = false;
        
        try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
             ZipOutputStream zipOutput = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {
            
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            
            while ((entry = zipInput.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // Read entry content
                ByteArrayOutputStream entryContent = new ByteArrayOutputStream();
                int bytesRead;
                while ((bytesRead = zipInput.read(buffer)) != -1) {
                    entryContent.write(buffer, 0, bytesRead);
                }
                byte[] entryBytes = entryContent.toByteArray();
                
                // Skip signature files to avoid MD5 digest errors
                if (isSignatureFile(entryName)) {
                    logger.debug("Skipping signature file: {}", entryName);
                    continue;
                }
                
                // Process CLASS files
                if (entryName.endsWith(".class") && !entryName.contains("module-info")) {
                    try {
                        // Check if this class should be excluded from processing
                        boolean shouldExclude = config != null && shouldExcludeClass(entryBytes, config);
                        
                        byte[] processedBytes;
                        boolean modified = false;
                        
                        if (shouldExclude) {
                            logger.debug("Class {} does not pass package filters, copying as-is", entryName);
                            processedBytes = entryBytes; // Use original bytes
                        } else {
                            processedBytes = restorer.restoreLineNumbers(entryBytes);
                            modified = !java.util.Arrays.equals(entryBytes, processedBytes);
                        }
                        
                        if (modified) {
                            anyModified = true;
                            logger.debug("Modified class in JAR: {}", entryName);
                        }
                        
                        // Create new entry with processed content
                        ZipEntry newEntry = new ZipEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        if (entry.getComment() != null) {
                            newEntry.setComment(entry.getComment());
                        }
                        
                        zipOutput.putNextEntry(newEntry);
                        zipOutput.write(processedBytes);
                        
                    } catch (Exception e) {
                        logger.warn("Failed to process class {} in JAR {}: {}", entryName, inputJar.getFileName(), e.getMessage());
                        
                        // Write original content on error
                        ZipEntry newEntry = new ZipEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        if (entry.getComment() != null) {
                            newEntry.setComment(entry.getComment());
                        }
                        
                        zipOutput.putNextEntry(newEntry);
                        zipOutput.write(entryBytes);
                    }
                } else {
                    // Copy non-CLASS files as-is
                    ZipEntry newEntry = new ZipEntry(entryName);
                    newEntry.setTime(entry.getTime());
                    if (entry.getComment() != null) {
                        newEntry.setComment(entry.getComment());
                    }
                    
                    zipOutput.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        zipOutput.write(entryBytes);
                    }
                }
                
                zipOutput.closeEntry();
            }
        }
        
        logger.debug("JAR processing completed with ZIP streams: {} (modified: {})", inputJar.getFileName(), anyModified);
        return anyModified;
    }
    
    /**
     * Check if a class should be excluded from line number processing based on its package
     * 
     * @param classBytes the class file bytes
     * @param config the command line configuration
     * @return true if the class should be excluded, false otherwise
     */
    private boolean shouldExcludeClass(byte[] classBytes, com.killer.perfectlinerestorer.Main.CommandLineConfig config) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            String className = reader.getClassName();
            return !config.shouldProcessClass(className);
        } catch (Exception e) {
            logger.warn("Failed to read class name for package filtering, copying as-is: {}", e.getMessage());
            return true;
        }
    }
    
    /**
     * Process a JAR file using ZIP streams (alternative implementation)
     * This method provides better control over entry attributes
     */
    public boolean processJarWithZip(Path inputJar, Path outputJar) throws IOException {
        logger.debug("Processing JAR with ZIP streams: {} -> {}", inputJar, outputJar);
        
        boolean anyModified = false;
        
        try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputJar)));
             ZipOutputStream zipOutput = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {
            
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            
            while ((entry = zipInput.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                // Read entry content
                ByteArrayOutputStream entryContent = new ByteArrayOutputStream();
                int bytesRead;
                while ((bytesRead = zipInput.read(buffer)) != -1) {
                    entryContent.write(buffer, 0, bytesRead);
                }
                byte[] entryBytes = entryContent.toByteArray();
                
                // Skip signature files to avoid MD5 digest errors
                if (isSignatureFile(entryName)) {
                    logger.debug("Skipping signature file: {}", entryName);
                    continue;
                }
                
                // Process CLASS files
                if (entryName.endsWith(".class") && !entryName.contains("module-info")) {
                    try {
                        byte[] processedBytes = restorer.restoreLineNumbers(entryBytes);
                        boolean modified = !java.util.Arrays.equals(entryBytes, processedBytes);
                        
                        if (modified) {
                            anyModified = true;
                            logger.debug("Modified class in JAR: {}", entryName);
                        }
                        
                        // Create new entry with processed content
                        ZipEntry newEntry = new ZipEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        newEntry.setComment(entry.getComment());
                        
                        zipOutput.putNextEntry(newEntry);
                        zipOutput.write(processedBytes);
                        
                    } catch (Exception e) {
                        logger.warn("Failed to process class {} in JAR {}: {}", entryName, inputJar.getFileName(), e.getMessage());
                        
                        // Write original content on error
                        ZipEntry newEntry = new ZipEntry(entryName);
                        newEntry.setTime(entry.getTime());
                        newEntry.setComment(entry.getComment());
                        
                        zipOutput.putNextEntry(newEntry);
                        zipOutput.write(entryBytes);
                    }
                } else {
                    // Copy non-CLASS files as-is
                    ZipEntry newEntry = new ZipEntry(entryName);
                    newEntry.setTime(entry.getTime());
                    newEntry.setComment(entry.getComment());
                    
                    zipOutput.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        zipOutput.write(entryBytes);
                    }
                }
                
                zipOutput.closeEntry();
            }
        }
        
        logger.debug("JAR processing completed: {} (modified: {})", inputJar.getFileName(), anyModified);
        return anyModified;
    }
    
    /**
     * Clean MD5 and SHA-1 digests from manifest entries
     * This prevents signature verification errors after modifying class files
     */
    private void cleanManifestDigests(Manifest manifest) {
        if (manifest == null) {
            return;
        }
        
        // Clean digests from individual entries
        for (String entryName : manifest.getEntries().keySet()) {
            java.util.jar.Attributes attributes = manifest.getEntries().get(entryName);
            if (attributes != null) {
                // Remove MD5 digest
                attributes.remove(new java.util.jar.Attributes.Name("MD5-Digest"));
                // Remove SHA-1 digest
                attributes.remove(new java.util.jar.Attributes.Name("SHA-1-Digest"));
                // Remove SHA-256 digest (if present)
                attributes.remove(new java.util.jar.Attributes.Name("SHA-256-Digest"));
                // Remove digest algorithms attribute
                attributes.remove(new java.util.jar.Attributes.Name("Digest-Algorithms"));
                
                logger.debug("Cleaned digests from manifest entry: {}", entryName);
            }
        }
        
        logger.debug("Manifest digests cleaned successfully");
    }
    
    /**
     * Check if the entry is a JAR signature file that should be skipped
     * to avoid MD5 digest verification errors after modifying class files
     */
    private boolean isSignatureFile(String entryName) {
        String upperName = entryName.toUpperCase();
        
        // Skip signature files in META-INF
        if (upperName.startsWith("META-INF/")) {
            // Signature files (.SF)
            if (upperName.endsWith(".SF")) {
                return true;
            }
            // Certificate files (.RSA, .DSA, .EC)
            if (upperName.endsWith(".RSA") || upperName.endsWith(".DSA") || upperName.endsWith(".EC")) {
                return true;
            }
            // Other signature-related files
            if (upperName.contains("SIG-")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validate JAR file integrity
     */
    public boolean validateJar(Path jarFile) {
        try (JarInputStream jarInput = new JarInputStream(Files.newInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jarInput.getNextJarEntry()) != null) {
                // Just iterate through entries to check for corruption
                byte[] buffer = new byte[8192];
                while (jarInput.read(buffer) != -1) {
                    // Read and discard
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("JAR validation failed for {}: {}", jarFile, e.getMessage());
            return false;
        }
    }
    
    /**
     * Get JAR file statistics
     */
    public JarStats getJarStats(Path jarFile) throws IOException {
        JarStats stats = new JarStats();
        
        try (JarInputStream jarInput = new JarInputStream(Files.newInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jarInput.getNextJarEntry()) != null) {
                stats.totalEntries++;
                
                if (entry.getName().endsWith(".class")) {
                    stats.classFiles++;
                } else if (entry.isDirectory()) {
                    stats.directories++;
                } else {
                    stats.otherFiles++;
                }
                
                // Read entry to get actual size
                byte[] buffer = new byte[8192];
                int totalBytes = 0;
                int bytesRead;
                while ((bytesRead = jarInput.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                }
                stats.totalSize += totalBytes;
            }
        }
        
        return stats;
    }
    
    /**
     * JAR file statistics
     */
    public static class JarStats {
        public int totalEntries = 0;
        public int classFiles = 0;
        public int directories = 0;
        public int otherFiles = 0;
        public long totalSize = 0;
        
        @Override
        public String toString() {
            return String.format("JarStats{entries=%d, classes=%d, dirs=%d, others=%d, size=%d bytes}", 
                               totalEntries, classFiles, directories, otherFiles, totalSize);
        }
    }
}