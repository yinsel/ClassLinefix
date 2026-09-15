package com.killer.perfectlinerestorer;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Perfect Line Restorer - Main Entry Point
 * 
 * A professional Java bytecode line number restoration tool with
 * multiple advanced restoration strategies.
 * 
 * @author Perfect Line Restorer Team
 * @version 1.0.0
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    private static final String PROGRAM_NAME = "ClassLinefix";
    private static final String VERSION = Main.class.getPackage().getImplementationVersion() != null
            ? Main.class.getPackage().getImplementationVersion() : "1.0.0";
    
    public static void main(String[] args) {
        try {
            CommandLineConfig config = parseCommandLine(args);
            if (config == null) {
                return; // Help was shown or parsing failed
            }
            
            logger.info("Perfect Line Restorer v{} starting...", VERSION);
            logger.info("Input directory: {}", config.getInputDir());
            logger.info("Output directory: {}", config.getOutputDir());
            
            // Validate input and output directories
            if (!validateDirectories(config)) {
                System.exit(1);
            }
            
            // Initialize and run the line restorer
            PerfectLineRestorer restorer = new PerfectLineRestorer(config);
            restorer.process();
            
            logger.info("Line number restoration completed successfully!");
            
        } catch (Exception e) {
            logger.error("Fatal error during execution: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Parse command line arguments
     */
    static CommandLineConfig parseCommandLine(String[] args) {
        Options options = createOptions();
        CommandLineParser parser = new DefaultParser();
        
        try {
            CommandLine cmd = parser.parse(options, args);
            
            // Show help if requested
            if (cmd.hasOption("h")) {
                showHelp(options);
                return null;
            }
            
            // Validate required options
            if (!cmd.hasOption("i") || !cmd.hasOption("o")) {
                System.err.println("Error: Both input (-i) and output (-o) directories are required.");
                showHelp(options);
                return null;
            }
            
            String inputDir = cmd.getOptionValue("i");
            String outputDir = cmd.getOptionValue("o");
            
            Set<String> excludePackages = parsePackageList(cmd.getOptionValue("p"));
            Set<String> whitelistPackages = parsePackageList(cmd.getOptionValue("w"));
            if (cmd.hasOption("w") && whitelistPackages.isEmpty()) {
                throw new ParseException("Whitelist (-w) must contain at least one package or class name");
            }

            // Parse skip inner classes option (default is false)
            boolean skipInnerClasses = false;
            if (cmd.hasOption("s")) {
                String skipValue = cmd.getOptionValue("s");
                if (skipValue != null) {
                    skipInnerClasses = Boolean.parseBoolean(skipValue);
                }
            }
            
            return new CommandLineConfig(inputDir, outputDir, excludePackages, whitelistPackages,
                    skipInnerClasses, cmd.hasOption("class-only"), cmd.hasOption("modified-only"));
            
        } catch (ParseException e) {
            System.err.println("Error parsing command line: " + e.getMessage());
            showHelp(options);
            return null;
        }
    }
    
    private static Set<String> parsePackageList(String value) {
        Set<String> packages = new HashSet<>();
        if (value != null) {
            for (String entry : value.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    packages.add(trimmed);
                }
            }
        }
        return packages;
    }

    /**
     * Create command line options
     */
    private static Options createOptions() {
        Options options = new Options();
        
        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show help message")
                .build());
        
        options.addOption(Option.builder("i")
                .longOpt("input")
                .hasArg()
                .argName("directory")
                .desc("Input directory containing JAR and CLASS files")
                .required(false) // We'll check this manually for better error messages
                .build());
        
        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("directory")
                .desc("Output directory for processed files")
                .required(false) // We'll check this manually for better error messages
                .build());
        
        options.addOption(Option.builder("p")
                .longOpt("packages")
                .hasArg()
                .argName("package1,package2,...")
                .desc("Comma-separated list of package names or full class names to exclude from line number processing (classes matching these patterns will be copied directly)")
                .required(false)
                .build());
        
        options.addOption(Option.builder("w")
                .longOpt("whitelist")
                .hasArg()
                .argName("package1,package2,...")
                .desc("Only process matching packages or full class names (same matching rules as -p; exclusions take precedence)")
                .build());

        options.addOption(Option.builder("m")
                .longOpt("modified-only")
                .desc("Only output files with modified CLASS bytecode; do not copy unchanged files or resources")
                .build());

        options.addOption(Option.builder("c")
                .longOpt("class-only")
                .desc("Only process standalone CLASS files; copy JAR files unchanged without opening them")
                .build());

        options.addOption(Option.builder("s")
                .longOpt("skip-inner")
                .hasArg()
                .argName("true|false")
                .desc("Skip inner classes and classes containing inner classes (default: false)")
                .required(false)
                .build());
        
        return options;
    }
    
    /**
     * Show help message
     */
    private static void showHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(100);
        
        System.out.println("ClassLinefix v" + VERSION);
        System.out.println("A professional Java bytecode line number restoration tool\n");
        
        formatter.printHelp(
            "java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar",
            "\nOptions:",
            options,
            "\nExample: java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar -i ./input-jars -o ./output-jars\n",
            true
        );
    }
    
    /**
     * Validate input and output paths (supports both directories and JAR files)
     */
    private static boolean validateDirectories(CommandLineConfig config) {
        Path inputPath = Paths.get(config.getInputDir());
        Path outputPath = Paths.get(config.getOutputDir());
        
        // Check if input path exists and is readable
        if (!Files.exists(inputPath)) {
            logger.error("Input path does not exist: {}", config.getInputDir());
            return false;
        }
        
        // Input can be either a directory or a JAR file
        if (!Files.isDirectory(inputPath) && !inputPath.toString().toLowerCase().endsWith(".jar")) {
            logger.error("Input path must be either a directory or a JAR file: {}", config.getInputDir());
            return false;
        }
        
        if (!Files.isReadable(inputPath)) {
            logger.error("Input path is not readable: {}", config.getInputDir());
            return false;
        }
        
        // Handle output path based on input type
        try {
            if (Files.isDirectory(inputPath)) {
                // Input is directory, output should be directory
                if (!Files.exists(outputPath)) {
                    Files.createDirectories(outputPath);
                    logger.info("Created output directory: {}", config.getOutputDir());
                }
                
                if (!Files.isDirectory(outputPath)) {
                    logger.error("Output path exists but is not a directory: {}", config.getOutputDir());
                    return false;
                }
                
                if (!Files.isWritable(outputPath)) {
                    logger.error("Output directory is not writable: {}", config.getOutputDir());
                    return false;
                }
            } else {
                // Input is JAR file, output should be JAR file
                if (!outputPath.toString().toLowerCase().endsWith(".jar")) {
                    logger.error("When input is a JAR file, output must also be a JAR file: {}", config.getOutputDir());
                    return false;
                }
                
                // Create parent directory if it doesn't exist
                Path parentDir = outputPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                    logger.info("Created output parent directory: {}", parentDir);
                }
                
                // Check if parent directory is writable
                if (parentDir != null && !Files.isWritable(parentDir)) {
                    logger.error("Output parent directory is not writable: {}", parentDir);
                    return false;
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to create or access output path: {}", e.getMessage());
            return false;
        }
        
        return true;
    }
    
    /**
     * Configuration class for command line options
     */
    public static class CommandLineConfig {
        private final String inputDir;
        private final String outputDir;
        private final Set<String> excludePackages;
        private final Set<String> whitelistPackages;
        private final boolean skipInnerClasses;
        private final boolean classOnly;
        private final boolean modifiedOnly;
        
        public CommandLineConfig(String inputDir, String outputDir) {
            this(inputDir, outputDir, new HashSet<>(), false);
        }
        
        public CommandLineConfig(String inputDir, String outputDir, Set<String> excludePackages) {
            this(inputDir, outputDir, excludePackages, false);
        }
        
        public CommandLineConfig(String inputDir, String outputDir, Set<String> excludePackages, boolean skipInnerClasses) {
            this(inputDir, outputDir, excludePackages, null, skipInnerClasses);
        }

        public CommandLineConfig(String inputDir, String outputDir, Set<String> excludePackages,
                                 Set<String> whitelistPackages, boolean skipInnerClasses) {
            this(inputDir, outputDir, excludePackages, whitelistPackages, skipInnerClasses, false);
        }

        public CommandLineConfig(String inputDir, String outputDir, Set<String> excludePackages,
                                 Set<String> whitelistPackages, boolean skipInnerClasses, boolean classOnly) {
            this(inputDir, outputDir, excludePackages, whitelistPackages, skipInnerClasses, classOnly, false);
        }

        public CommandLineConfig(String inputDir, String outputDir, Set<String> excludePackages,
                                 Set<String> whitelistPackages, boolean skipInnerClasses, boolean classOnly,
                                 boolean modifiedOnly) {
            this.inputDir = inputDir;
            this.outputDir = outputDir;
            this.excludePackages = excludePackages != null ? new HashSet<>(excludePackages) : new HashSet<>();
            this.whitelistPackages = whitelistPackages != null ? new HashSet<>(whitelistPackages) : new HashSet<>();
            this.skipInnerClasses = skipInnerClasses;
            this.classOnly = classOnly;
            this.modifiedOnly = modifiedOnly;
        }
        
        public String getInputDir() {
            return inputDir;
        }
        
        public String getOutputDir() {
            return outputDir;
        }
        
        public Set<String> getExcludePackages() {
            return new HashSet<>(excludePackages);
        }
        
        public boolean isSkipInnerClasses() {
            return skipInnerClasses;
        }
        
        public boolean isClassOnly() {
            return classOnly;
        }

        public boolean isModifiedOnly() {
            return modifiedOnly;
        }

        public Set<String> getWhitelistPackages() {
            return new HashSet<>(whitelistPackages);
        }

        public boolean shouldExcludePackage(String className) {
            return matchesPackagePattern(className, excludePackages);
        }

        public boolean shouldProcessClass(String className) {
            return className != null
                    && (whitelistPackages.isEmpty() || matchesPackagePattern(className, whitelistPackages))
                    && !shouldExcludePackage(className);
        }

        // Keep whitelist and -p semantics identical, including the existing package-name heuristic.
        private boolean matchesPackagePattern(String className, Set<String> patterns) {
            if (className == null || patterns.isEmpty()) {
                return false;
            }
            String dottedName = className.replace('/', '.');
            for (String pattern : patterns) {
                if (dottedName.equals(pattern)
                        || (isPackageName(pattern) && dottedName.startsWith(pattern + "."))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Check if the given string is likely a package name rather than a full class name
         * This is a heuristic: package names typically use lowercase, class names start with uppercase
         */
        private boolean isPackageName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            
            // Split by dots and check the last segment
            String[] segments = name.split("\\.");
            if (segments.length == 0) {
                return false;
            }
            
            String lastSegment = segments[segments.length - 1];
            // If last segment starts with lowercase, it's likely a package name
            // If it starts with uppercase, it's likely a class name
            return lastSegment.length() > 0 && Character.isLowerCase(lastSegment.charAt(0));
        }
        
        @Override
        public String toString() {
            return String.format("CommandLineConfig{inputDir='%s', outputDir='%s', excludePackages=%s, whitelistPackages=%s, skipInnerClasses=%s, classOnly=%s, modifiedOnly=%s}",
                    inputDir, outputDir, excludePackages, whitelistPackages, skipInnerClasses, classOnly, modifiedOnly);
        }
    }
}