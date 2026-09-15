package com.killer.perfectlinerestorer;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
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
            logger.info("Input path: {}", config.getInputDir());
            logger.info("Output path: {}", config.getOutputDir());
            
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
            if (!cmd.hasOption("i")) {
                System.err.println("Error: Input (-i) directory, CLASS or JAR file is required.");
                showHelp(options);
                return null;
            }
            
            String inputDir = cmd.getOptionValue("i");
            
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
            
            return new CommandLineConfig(inputDir, excludePackages, whitelistPackages,
                    skipInnerClasses, cmd.hasOption("class-only"), cmd.hasOption("debug-info"), cmd.hasOption("rebuild-lines"));
            
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
                .argName("path")
                .desc("Input directory, CLASS file or JAR file")
                .required(false) // We'll check this manually for better error messages
                .build());

        
        options.addOption(Option.builder("p")
                .longOpt("packages")
                .hasArg()
                .argName("package1,package2,...")
                .desc("Comma-separated list of package names or full class names to exclude from line number processing")
                .required(false)
                .build());
        
        options.addOption(Option.builder("w")
                .longOpt("whitelist")
                .hasArg()
                .argName("package1,package2,...")
                .desc("Only process matching packages or full class names (same matching rules as -p; exclusions take precedence)")
                .build());

        options.addOption(Option.builder()
                .longOpt("rebuild-lines")
                .desc("Replace existing line tables with synthetic statement boundaries; implies --debug-info")
                .build());

        options.addOption(Option.builder("d")
                .longOpt("debug-info")
                .desc("Fill missing source/line/local-variable debug metadata; use synthetic variable names (default: off)")
                .build());


        options.addOption(Option.builder("c")
                .longOpt("class-only")
                .desc("Only process standalone CLASS files; skip directory JARs, copy a single JAR unchanged")
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
            "\nDirectory: back up changed files to <input>-bak, replace originals, copy results to <input>-out.\n"
                    + "Backup/output directories must not already exist. Relative paths are preserved.\n"
                    + "Single file: write <name>-fix.class or <name>-fix.jar beside the original.\n"
                    + "\nExamples:\n"
                    + "  java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar -i ./input\n"
                    + "  java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar -i ./Example.class\n"
                    + "  java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar -i ./app.jar -d\n"
                    + "  java -jar " + PROGRAM_NAME + "-" + VERSION + ".jar -i ./input -c -w com.api.doc\n",
            true
        );
    }
    
    /**
     * Validate input paths (directories, CLASS and JAR files)
     */
    private static boolean validateDirectories(CommandLineConfig config) {
        Path input = Paths.get(config.getInputDir());
        String name = input.toString().toLowerCase(Locale.ROOT);
        if (!Files.isReadable(input) || (!Files.isDirectory(input)
                && !(Files.isRegularFile(input) && (name.endsWith(".class") || name.endsWith(".jar"))))) {
            logger.error("Input must be a readable directory, CLASS or JAR file: {}", input);
            return false;
        }
        return true;
    }

    /**
     * Configuration class for command line options
     */
    public static class CommandLineConfig {
        private final String inputDir;
        private final Set<String> excludePackages;
        private final Set<String> whitelistPackages;
        private final boolean skipInnerClasses;
        private final boolean classOnly;
        private final boolean debugInfo;
        private final boolean rebuildLines;
        
        public CommandLineConfig(String inputDir) {
            this(inputDir, null, null, false, false, false, false);
        }

        public CommandLineConfig(String inputDir, Set<String> excludePackages,
                                 Set<String> whitelistPackages, boolean skipInnerClasses,
                                 boolean classOnly, boolean debugInfo, boolean rebuildLines) {
            this.inputDir = inputDir;
            this.excludePackages = excludePackages != null ? new HashSet<>(excludePackages) : new HashSet<>();
            this.whitelistPackages = whitelistPackages != null ? new HashSet<>(whitelistPackages) : new HashSet<>();
            this.skipInnerClasses = skipInnerClasses;
            this.classOnly = classOnly;
            this.debugInfo = debugInfo || rebuildLines;
            this.rebuildLines = rebuildLines;
        }

        public String getInputDir() {
            return inputDir;
        }
        
        public String getOutputDir() {
            Path input = Paths.get(inputDir).toAbsolutePath().normalize();
            if (Files.isDirectory(input)) {
                return sibling(input, "-out").toString();
            }
            String name = input.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String output = dot > 0 ? name.substring(0, dot) + "-fix" + name.substring(dot) : name + "-fix";
            return input.resolveSibling(output).toString();
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

        public String getBackupDir() {
            return sibling(Paths.get(inputDir).toAbsolutePath().normalize(), "-bak").toString();
        }

        private Path sibling(Path input, String suffix) {
            if (input.getFileName() == null) {
                throw new IllegalArgumentException("A filesystem root cannot be used as the input directory");
            }
            return input.resolveSibling(input.getFileName().toString() + suffix);
        }

        public boolean isRebuildLines() { return rebuildLines; }

        public boolean isDebugInfo() {
            return debugInfo;
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
            return String.format("CommandLineConfig{inputDir='%s', outputDir='%s', excludePackages=%s, whitelistPackages=%s, skipInnerClasses=%s, classOnly=%s, debugInfo=%s}",
                    inputDir, getOutputDir(), excludePackages, whitelistPackages, skipInnerClasses, classOnly, debugInfo);
        }
    }
}