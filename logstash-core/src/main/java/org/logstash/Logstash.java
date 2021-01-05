/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.logstash;

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jruby.Ruby;
import org.jruby.RubyException;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubySystemExit;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Logstash Main Entrypoint.
 */
public final class Logstash implements Runnable, AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(Logstash.class);

    /**
     * JRuby Runtime Environment.
     */
    private final Ruby ruby;

    /**
     * Main Entrypoint.
     * Requires environment {@code "LS_HOME"} to be set to the Logstash root directory.
     * @param args Logstash CLI Arguments
     */
    public static void main(final String... args) {
        final String lsHome = System.getenv("LS_HOME");
        if (lsHome == null) {
            throw new IllegalStateException(
                    "LS_HOME environment variable must be set. This is likely a bug that should be reported."
            );
        }
        configureNashornDeprecationSwitchForJavaAbove11();
        installGlobalUncaughtExceptionHandler();

        final Path home = Paths.get(lsHome).toAbsolutePath();
        try (
                final Logstash logstash = new Logstash(home, args, System.out, System.err, System.in)
        ) {
            logstash.run();
        } catch (final IllegalStateException e) {
            String errorMessage = null;
            if (e.getMessage() != null && e.getMessage().contains("Could not load FFI Provider")) {
                errorMessage =
                        "Error accessing temp directory: " + System.getProperty("java.io.tmpdir") +
                        " this often occurs because the temp directory has been mounted with NOEXEC or" +
                        " the Logstash user has insufficient permissions on the directory. \n" +
                        "Possible workarounds include setting the -Djava.io.tmpdir property in the jvm.options" +
                        "file to an alternate directory or correcting the Logstash user's permissions.";
            }
            handleFatalError("fatal error", e, errorMessage);
        } catch (final Throwable t) {
            handleFatalError("fatal error", t, null);
        }

        System.exit(0);
    }

    private static void configureNashornDeprecationSwitchForJavaAbove11() {
        final String javaVersion = System.getProperty("java.version");
        // match version 1.x.y, 9.x.y and 10.x.y
        if (!javaVersion.matches("^1\\.\\d\\..*") && !javaVersion.matches("^(9|10)\\.\\d\\..*")) {
            // Avoid Nashorn deprecation logs in JDK >= 11
            System.setProperty("nashorn.args", "--no-deprecation-warning");
        }
    }

    private static void installGlobalUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            if (e instanceof Error) {
                handleFatalError("uncaught error (in thread " + thread.getName() + ")",  e, null);
            } else {
                LOGGER.error("uncaught exception (in thread " + thread.getName() + ")", e);
            }
        });
    }

    private static void handleFatalError(String message, Throwable t, String supplementaryErrorMessage) {
        LOGGER.fatal(message, t);
        if (supplementaryErrorMessage != null) {
            LOGGER.error(supplementaryErrorMessage);
        }

        if (t instanceof InternalError) {
            halt(128);
        } else if (t instanceof OutOfMemoryError) {
            halt(127);
        } else if (t instanceof StackOverflowError) {
            halt(126);
        } else if (t instanceof UnknownError) {
            halt(125);
        } else if (t instanceof IOError) {
            halt(124);
        } else if (t instanceof LinkageError) {
            halt(123);
        } else if (t instanceof Error) {
            halt(120);
        }

        System.exit(1);
    }

    private static void halt(final int status) {
        AccessController.doPrivileged(new PrivilegedHaltAction(status));
    }

    private static class PrivilegedHaltAction implements PrivilegedAction<Void> {

        private final int status;

        private PrivilegedHaltAction(final int status) {
            this.status = status;
        }

        @Override
        public Void run() {
            // we halt to prevent shutdown hooks from running
            Runtime.getRuntime().halt(status);
            return null;
        }

    }

    /**
     * Ctor.
     * @param home Logstash Root Directory
     * @param args Commandline Arguments
     * @param output Output Stream Capturing StdOut
     * @param error Output Stream Capturing StdErr
     * @param input Input Stream Capturing StdIn
     */
    Logstash(final Path home, final String[] args, final PrintStream output,
        final PrintStream error, final InputStream input) {
        final RubyInstanceConfig config = buildConfig(home, args);
        config.setOutput(output);
        config.setError(error);
        config.setInput(input);
        ruby = Ruby.newInstance(config);
    }

    @Override
    public void run() {
        // @todo: Refactor codebase to not rely on global constant for Ruby Runtime
        if (RubyUtil.RUBY != ruby) {
            throw new IllegalStateException(
                "More than one JRuby Runtime detected in the current JVM!"
            );
        }
        final RubyInstanceConfig config = ruby.getInstanceConfig();
        try (InputStream script = config.getScriptSource()) {
            Thread.currentThread().setContextClassLoader(ruby.getJRubyClassLoader());
            ruby.runFromMain(script, config.displayedFileName());
        } catch (final RaiseException ex) {
            final RubyException re = ex.getException();
            if (re instanceof RubySystemExit) {
                IRubyObject success = ((RubySystemExit) re).success_p();
                if (!success.isTrue()) {
                    uncleanShutdown(ex);
                }
            } else {
                uncleanShutdown(ex);
            }
        } catch (final IOException ex) {
            uncleanShutdown(ex);
        }
    }

    @Override
    public void close() {
        ruby.tearDown(false);
    }

    /**
     * Sets up the correct {@link RubyInstanceConfig} for a given Logstash installation and set of
     * CLI arguments.
     * @param home Logstash Root Path
     * @param args Commandline Arguments Passed to Logstash
     * @return RubyInstanceConfig
     */
    private static RubyInstanceConfig buildConfig(final Path home, final String[] args) {
        final String[] arguments = new String[args.length + 2];
        System.arraycopy(args, 0, arguments, 2, args.length);
        arguments[0] = safePath(home, "lib", "bootstrap", "environment.rb");
        arguments[1] = safePath(home, "logstash-core", "lib", "logstash", "runner.rb");
        final RubyInstanceConfig config = new RubyInstanceConfig();
        config.processArguments(arguments);
        return config;
    }

    /**
     * Builds the correct path for a file under the given Logstash root and defined by its sub path
     * elements relative to the Logstash root.
     * Ensures that the file exists and throws an exception of it's missing.
     * This is done to avoid hard to interpret errors thrown by JRuby that could result from missing
     * Ruby bootstrap scripts.
     * @param home Logstash Root Path
     * @param subs Path elements relative to {@code home}
     * @return Absolute Path a File under the Logstash Root.
     */
    private static String safePath(final Path home, final String... subs) {
        Path resolved = home;
        for (final String element : subs) {
            resolved = resolved.resolve(element);
        }
        if (!resolved.toFile().exists()) {
            throw new IllegalArgumentException(String.format("Missing: %s.", resolved));
        }
        return resolved.toString();
    }

    private static void uncleanShutdown(final Exception ex) {
        throw new IllegalStateException("Logstash stopped processing because of an error: " + ex.getMessage(), ex);
    }

}
