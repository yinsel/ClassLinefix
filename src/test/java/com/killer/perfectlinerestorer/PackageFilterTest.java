package com.killer.perfectlinerestorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class PackageFilterTest {
    @ParameterizedTest
    @CsvSource({
            "com.example, com/example/Foo, true",
            "com.example, com.example.sub.Foo, true",
            "com.example, com.examples.Foo, false",
            "com.example, org.com.example.Foo, false",
            "com.example.Foo, com/example/Foo, true",
            "com.example.Foo, com.example.FooBar, false",
            "com.example.Foo, com.example.Foo$Inner, false",
            "com.example, com.example.Foo$Inner, true",
            "com.Example, com.Example.Foo, false",
            "com.example.*, com.example.Foo, false",
            "com/example, com/example/Foo, false",
            "Foo, Foo, true"
    })
    void whitelistAndExclusionsUseIdenticalMatching(String pattern, String name, boolean matches) {
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", "input", "-w", pattern});
        Main.CommandLineConfig excluded = Main.parseCommandLine(new String[]{
                "-i", "input", "-p", pattern});
        assertNotNull(config);
        assertEquals(matches, config.shouldProcessClass(name));
        assertEquals(matches, excluded.shouldExcludePackage(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-w", "--whitelist"})
    void parsesListsAndGivesExclusionsPrecedence(String option) {
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", "input", option,
                " com.example , org.other.One, ,com.example ", "-p", " com.example.internal , ", "-s", "true"});
        assertNotNull(config);
        assertEquals(new HashSet<>(Arrays.asList("com.example", "org.other.One")), config.getWhitelistPackages());
        assertTrue(config.shouldProcessClass("com.example.Foo"));
        assertTrue(config.shouldProcessClass("org.other.One"));
        assertFalse(config.shouldProcessClass("org.other.Two"));
        assertFalse(config.shouldProcessClass("com.example.internal.Secret"));
        assertTrue(config.isSkipInnerClasses());
        config.getWhitelistPackages().clear();
        assertFalse(config.shouldProcessClass("outside.Foo"));
    }

    @Test
    void omittingWhitelistPreservesExistingBehaviorAndConstructors() {
        Main.CommandLineConfig config = Main.parseCommandLine(new String[]{
                "-i", "input", "-p", "org.thirdparty"});
        assertNotNull(config);
        assertTrue(config.shouldProcessClass("com.example.Foo"));
        assertFalse(config.shouldProcessClass("org.thirdparty.Foo"));
        assertTrue(new Main.CommandLineConfig("in").shouldProcessClass("anything.Foo"));
        assertFalse(new Main.CommandLineConfig("in", config.getExcludePackages(), null, false, false, false, false)
                .shouldProcessClass("org.thirdparty.Foo"));
    }

    @Test
    void rejectsEmptyExplicitWhitelist() {
        assertNull(Main.parseCommandLine(new String[]{"-i", "input", "-w", " , , "}));
    }
}
