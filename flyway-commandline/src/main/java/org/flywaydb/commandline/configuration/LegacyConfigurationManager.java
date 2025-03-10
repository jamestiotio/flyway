/*
 * Copyright (C) Red Gate Software Ltd 2010-2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.commandline.configuration;

import lombok.CustomLog;
import org.flywaydb.commandline.Main;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.configuration.ConfigUtils;
import org.flywaydb.core.internal.database.DatabaseType;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.util.ClassUtils;
import org.flywaydb.core.internal.util.StringUtils;
import org.flywaydb.core.extensibility.LicenseGuard;
import org.flywaydb.core.extensibility.Tier;

import java.io.Console;
import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.flywaydb.core.internal.configuration.ConfigUtils.DEFAULT_CLI_SQL_LOCATION;
import static org.flywaydb.core.internal.configuration.ConfigUtils.makeRelativeLocationsBasedOnWorkingDirectory;

@CustomLog
public class LegacyConfigurationManager implements ConfigurationManager {

    public Configuration getConfiguration(CommandLineArguments commandLineArguments) {

        Map<String, String> config = new HashMap<>();
        String workingDirectory = commandLineArguments.isWorkingDirectorySet() ? commandLineArguments.getWorkingDirectory() : ClassUtils.getInstallDir(Main.class);

        File jarDir = new File(workingDirectory, "jars");
        ConfigUtils.warnIfUsingDeprecatedMigrationsFolder(jarDir, ".jar");
        if (jarDir.exists()) {
            config.put(ConfigUtils.JAR_DIRS, jarDir.getAbsolutePath());
        }

        Map<String, String> envVars = ConfigUtils.environmentVariablesToPropertyMap();

        loadConfigurationFromConfigFiles(config, commandLineArguments, envVars);

        config.putAll(envVars);
        config = overrideConfiguration(config, commandLineArguments.getConfiguration(false));

        File sqlFolder = new File(workingDirectory, DEFAULT_CLI_SQL_LOCATION);
        if (ConfigUtils.shouldUseDefaultCliSqlLocation(sqlFolder, StringUtils.hasText(config.get(ConfigUtils.LOCATIONS)))) {
            config.put(ConfigUtils.LOCATIONS, "filesystem:" + sqlFolder.getAbsolutePath());
        }

        if (commandLineArguments.isWorkingDirectorySet()) {
            makeRelativeLocationsBasedOnWorkingDirectory(commandLineArguments.getWorkingDirectory(), config);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        List<File> jarFiles = new ArrayList<>(CommandLineConfigurationUtils.getJdbcDriverJarFiles());

        String jarDirs = config.get(ConfigUtils.JAR_DIRS);
        if (StringUtils.hasText(jarDirs)) {
            jarFiles.addAll(CommandLineConfigurationUtils.getJavaMigrationJarFiles(StringUtils.tokenizeToStringArray(jarDirs.replace(File.pathSeparator, ","), ",")));
        }

        if (!jarFiles.isEmpty()) {
            classLoader = ClassUtils.addJarsOrDirectoriesToClasspath(classLoader, jarFiles);
        }

        ConfigUtils.dumpConfigurationMap(config);
        filterProperties(config);

        final FluentConfiguration configuration = new FluentConfiguration(classLoader).configuration(config).workingDirectory(workingDirectory);

        if (!commandLineArguments.shouldSuppressPrompt()) {
            promptForCredentialsIfMissing(config, configuration);
        }

        return configuration;
    }

    protected void loadConfigurationFromConfigFiles(Map<String, String> config, CommandLineArguments commandLineArguments, Map<String, String> envVars) {
        String encoding = determineConfigurationFileEncoding(commandLineArguments, envVars);
        File installationDir = new File(ClassUtils.getInstallDir(Main.class));

        config.putAll(ConfigUtils.loadDefaultConfigurationFiles(installationDir, encoding));

        for (File configFile : determineLegacyConfigFilesFromArgs(commandLineArguments, envVars)) {
            config.putAll(ConfigUtils.loadConfigurationFile(configFile, encoding, true));
        }
    }

    /**
     * @return The encoding. (default: UTF-8)
     */
    private static String determineConfigurationFileEncoding(CommandLineArguments commandLineArguments, Map<String, String> envVars) {
        if (envVars.containsKey(ConfigUtils.CONFIG_FILE_ENCODING)) {
            return envVars.get(ConfigUtils.CONFIG_FILE_ENCODING);
        }

        if (commandLineArguments.isConfigFileEncodingSet()) {
            return commandLineArguments.getConfigFileEncoding();
        }

        return "UTF-8";
    }

    private static List<File> determineLegacyConfigFilesFromArgs(CommandLineArguments commandLineArguments, Map<String, String> envVars) {

        String workingDirectory = commandLineArguments.isWorkingDirectorySet() ? commandLineArguments.getWorkingDirectory() : null;

        Stream<String> configFilePaths = envVars.containsKey(ConfigUtils.CONFIG_FILES) ?
                Arrays.stream(StringUtils.tokenizeToStringArray(envVars.get(ConfigUtils.CONFIG_FILES), ",")) :
                commandLineArguments.getConfigFiles().stream();

        return configFilePaths.map(path -> Paths.get(path).isAbsolute() ? new File(path) : new File(workingDirectory, path)).collect(Collectors.toList());
    }

    private static Map<String, String> overrideConfiguration(Map<String, String> existingConfiguration, Map<String, String> newConfiguration) {
        Map<String, String> combinedConfiguration = new HashMap<>();

        combinedConfiguration.putAll(existingConfiguration);
        combinedConfiguration.putAll(newConfiguration);

        return combinedConfiguration;
    }

    /**
     * If no user or password has been provided, prompt for it. If you want to avoid the prompt, pass in an empty user
     * or password.
     *
     * @param config The properties object to load to configuration into.
     * @return
     */
    private void promptForCredentialsIfMissing(final Map<String, String> config,  final FluentConfiguration configuration) {
        final Console console = System.console();
        if (console == null) {
            // We are running in an automated build. Prompting is not possible.
            return;
        }

        if (!config.containsKey(ConfigUtils.URL)) {
            // URL is not set. We are doomed for failure anyway.
            return;
        }

        final String url = config.get(ConfigUtils.URL);

        boolean interactivePrompted =  false;

        final boolean hasUser = config.containsKey(ConfigUtils.USER);

        if (!hasUser



                && needsUser(url, config.getOrDefault(ConfigUtils.PASSWORD, null), configuration)) {
            configuration.dataSource(configuration.getUrl(),console.readLine("Database user: "), configuration.getPassword());
            interactivePrompted = true;
        }

        final boolean hasPassword = config.containsKey(ConfigUtils.PASSWORD);

        if (!hasPassword



                && needsPassword(url, config.get(ConfigUtils.USER), configuration)) {
            final char[] password = console.readPassword("Database password: ");
            configuration.dataSource(configuration.getUrl(), configuration.getUser(), password == null ? "" : String.valueOf(password));
            interactivePrompted = true;
        }

        if (interactivePrompted) {
            LOG.warn("Interactive prompt behavior is deprecated and will be removed in a future release - please consider alternatives like secrets management tools or environment variables");
        }
    }

    /**
     * Detect whether the JDBC URL specifies a known authentication mechanism that does not need a username.
     */
    boolean needsUser(String url, String password, Configuration configuration) {
        DatabaseType databaseType = DatabaseTypeRegister.getDatabaseTypeForUrl(url);
        if (databaseType.detectUserRequiredByUrl(url)) {









            return true;

        }

        return false;
    }

    /**
     * Detect whether the JDBC URL specifies a known authentication mechanism that does not need a password.
     */
    boolean needsPassword(String url, String username, Configuration configuration) {
        DatabaseType databaseType = DatabaseTypeRegister.getDatabaseTypeForUrl(url);
        if (databaseType.detectPasswordRequiredByUrl(url)) {









            return true;
        }

        return false;
    }

    /**
     * Filters the properties to remove the Flyway Commandline-specific ones.
     */
    private void filterProperties(Map<String, String> config) {
        config.remove(ConfigUtils.JAR_DIRS);
        config.remove(ConfigUtils.CONFIG_FILES);
        config.remove(ConfigUtils.CONFIG_FILE_ENCODING);
    }
}