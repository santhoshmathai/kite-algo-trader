package com.example.trading;

import org.junit.jupiter.api.Test;

/** Runs the same assertions with Maven Surefire and the dependency-free local build. */
public final class FoundationTest {
    @Test public void foundationRegressionSuite() { FoundationChecks.runAll(); }
}
