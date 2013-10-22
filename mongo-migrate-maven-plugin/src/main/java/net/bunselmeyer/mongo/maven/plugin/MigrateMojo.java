/*
 * Copyright 2012 William L. Bunselmeyer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.bunselmeyer.mongo.maven.plugin;

import com.google.common.base.Function;
import com.google.common.collect.*;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import com.mongodb.WriteConcern;
import net.bunselmeyer.mongo.annotations.Connection;
import net.bunselmeyer.mongo.migrate.Migration;
import net.vz.mongodb.jackson.JacksonDBCollection;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.bson.types.ObjectId;
import org.codehaus.jackson.map.ObjectMapper;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Maven mojo for running mongo migrations
 * Usage:
 * mvn mongo:migrate
 */
@Mojo(
        name = "migrate",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.TEST,
        requiresDependencyCollection = ResolutionScope.TEST
)
public class MigrateMojo extends AbstractMojo {

    @Parameter(alias = "package", required = true)
    private String migrationPackage;

    @Parameter(alias = "host")
    private String host;

    @Parameter(alias = "port", defaultValue = "27017")
    private String port;


    private final ObjectMapper _objectMapper = new ObjectMapper();

    protected enum MIGRATION_CHECK {
        ERROR, WARNING, GOOD
    }

    public void execute() throws MojoExecutionException {

        if (StringUtils.isBlank(host)) {
            host = "localhost";
        }

        Set<Class<? extends Migration>> allMigrations = scanProjectForMigrations();

        ImmutableListMultimap<MIGRATION_CHECK, MigrationDetails> statusIndex = buildStatusIndex(allMigrations);

        ImmutableList<MigrationDetails> errors = statusIndex.get(MIGRATION_CHECK.ERROR);
        if (!errors.isEmpty()) {
            getLog().error("Fail: Please correct the following issues...");
            for (MigrationDetails error : errors) {
                getLog().error("    " + error.migration.getName() + ": " + error.message);
            }
            return;
        }

        ImmutableList<MigrationDetails> warnings = statusIndex.get(MIGRATION_CHECK.WARNING);
        if (!warnings.isEmpty()) {
            getLog().warn("Warnings...");
            for (MigrationDetails warning : warnings) {
                getLog().warn("    " + warning.migration.getName() + ": " + warning.message);
            }
        }

        ImmutableList<MigrationDetails> goodMigrations = statusIndex.get(MIGRATION_CHECK.GOOD);

        getLog().info("Found " + goodMigrations.size() + " migrations.");

        ImmutableListMultimap<String, MigrationDetails> index = buildIndex(goodMigrations);

        List<String> keys = Lists.newArrayList(index.keySet());
        Collections.sort(keys);

        for (String connectionDef : keys) {
            runMigrations(Lists.newArrayList(index.get(connectionDef)));
        }
    }

    private Set<Class<? extends Migration>> scanProjectForMigrations() throws MojoExecutionException {
        ConfigurationBuilder configuration = new ConfigurationBuilder() //
                .addUrls(Sets.newHashSet(buildOutputDirectoryUrl())) //
                .addClassLoader(buildProjectClassLoader());

        if (StringUtils.isNotBlank(migrationPackage)) {
            FilterBuilder filterBuilder = new FilterBuilder();
            filterBuilder.include(FilterBuilder.prefix(migrationPackage));
            configuration.filterInputsBy(filterBuilder);
        }

        Reflections reflections = new Reflections(configuration);
        return reflections.getSubTypesOf(Migration.class);
    }

    protected ImmutableListMultimap<MIGRATION_CHECK, MigrationDetails> buildStatusIndex(Set<Class<? extends Migration>> allMigrations) {
        Iterable<MigrationDetails> migrationStatus = Iterables.transform(allMigrations, new Function<Class<? extends Migration>, MigrationDetails>() {
            public MigrationDetails apply(Class<? extends Migration> input) {
                if (input == null) {
                    return new MigrationDetails(MIGRATION_CHECK.ERROR, "Failed to load migration from classloader.", input);
                }
                Connection connection = input.getAnnotation(Connection.class);
                if (connection == null) {
                    return new MigrationDetails(MIGRATION_CHECK.WARNING, "Migration does not have @Connection", input);
                }

                if (StringUtils.isBlank(connection.db())) {
                    return new MigrationDetails(MIGRATION_CHECK.ERROR, "Empty db property in @Connection", input);
                }

                if (StringUtils.isBlank(connection.version())) {
                    return new MigrationDetails(MIGRATION_CHECK.ERROR, "Empty version property in @Connection", input);
                }

                try {
                    DateTime version = DateTime.parse(connection.version());
                    String host = StringUtils.isNotBlank(connection.host()) ? connection.host() : MigrateMojo.this.host;
                    return version != null ? //
                            new MigrationDetails(input, version, host, connection.db()) : //
                            new MigrationDetails(MIGRATION_CHECK.ERROR, "Failed to parse @version to timestamp in @Connection", input);
                } catch (Exception e) {
                    return new MigrationDetails(MIGRATION_CHECK.ERROR, "Failed to parse @version to timestamp in @Connection", input);
                }
            }
        });

        return Multimaps.index(migrationStatus, new Function<MigrationDetails, MIGRATION_CHECK>() {
            public MIGRATION_CHECK apply(MigrationDetails input) {
                return input.status;
            }
        });
    }

    protected ImmutableListMultimap<String, MigrationDetails> buildIndex(Iterable<MigrationDetails> migrations) {
        return Multimaps.index(migrations, new Function<MigrationDetails, String>() {
            public String apply(MigrationDetails input) {
                return input.host + "," + input.db;
            }
        });
    }

    private void runMigrations(List<MigrationDetails> migrations) {
        if (migrations.isEmpty()) {
            return;
        }

        MigrationDetails migrationDetails = migrations.get(0);

        Mongo mongo;
        try {
            mongo = new Mongo(migrationDetails.host, Integer.parseInt(port));
        } catch (UnknownHostException e) {
            getLog().error("Failed to connect to " + migrationDetails.host + ":" + port);
            return;
        } catch (NumberFormatException e) {
            getLog().error("Invalid port: " + port);
            return;
        }

        mongo.setWriteConcern(WriteConcern.SAFE);

        DB db = mongo.getDB(migrationDetails.db);

        JacksonDBCollection<MigrationVersionDetails, ObjectId> migrationVersionCollection = createMigrationVersionCollection(db);

        getLog().info("Running migrations. Host: " + migrationDetails.host + ". DB: " + migrationDetails.db);

        sortMigrationDetails(migrations);

        Class<? extends Migration> lastMigration = null;
        try {
            for (MigrationDetails details : migrations) {
                lastMigration = details.migration;
                Migration m = details.migration.newInstance();

                MigrationVersionDetails versionDetails = new MigrationVersionDetails();
                versionDetails.setMigrationName(details.migration.getName());
                versionDetails.setVersion(details.version);

                if (migrationVersionCollection.getCount(versionDetails) == 0) {
                    m.up(db);
                    db.getLastError().throwOnError();
                    getLog().info("    " + details.migration.getName() + ", v" + details.version + " migration complete");
                    versionDetails.setRun(DateTime.now(DateTimeZone.UTC));
                    migrationVersionCollection.insert(versionDetails);
                } else {
                    getLog().info("    " + details.migration.getName() + ", v" + details.version + " was already run");
                }
            }
        } catch (Exception e) {
            String name = lastMigration != null ? lastMigration.getName() : "";
            getLog().info("    FAIL! " + name + " migration error", e);
        } finally {
            mongo.close();
        }
    }

    private JacksonDBCollection<MigrationVersionDetails, ObjectId> createMigrationVersionCollection(DB db) {
        DBCollection dbCollection = db.getCollection(MigrationVersionDetails.class.getSimpleName());
        return JacksonDBCollection.wrap(dbCollection, MigrationVersionDetails.class, ObjectId.class, _objectMapper);
    }

    protected void sortMigrationDetails(List<MigrationDetails> migrations) {
        Collections.sort(migrations, new Comparator<MigrationDetails>() {
            public int compare(MigrationDetails o1, MigrationDetails o2) {
                DateTime v1 = o1.version;
                DateTime v2 = o2.version;
                return v1.compareTo(v2);
            }
        });
    }

    private URLClassLoader buildProjectClassLoader() throws MojoExecutionException {
        getLog().debug("adding all artifacts to classLoader");
        List<URL> urls = new ArrayList<URL>();


        for (Object artifact : getProject().getArtifacts()) {
            try {
                urls.add(((Artifact) artifact).getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

        urls.add(buildOutputDirectoryUrl());

        getLog().debug("urls = \n" + urls.toString().replace(",", "\n"));

        return new URLClassLoader(urls.toArray(new URL[urls.size()]), getClass().getClassLoader());
    }

    private URL buildOutputDirectoryUrl() throws MojoExecutionException {
        try {
            File outputDirectoryFile = new File(getProject().getBuild().getOutputDirectory() + "/");
            return outputDirectoryFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private MavenProject getProject() {
        return (MavenProject) getPluginContext().get("project");
    }

    protected static class MigrationDetails {
        public MIGRATION_CHECK status;
        public String message;
        public Class<? extends Migration> migration;
        public DateTime version;
        public String host;
        public String db;

        private MigrationDetails(MIGRATION_CHECK status, String message, Class<? extends Migration> migration) {
            this.status = status;
            this.message = message;
            this.migration = migration;
        }

        protected MigrationDetails(Class<? extends Migration> migration, DateTime version, String host, String db) {
            this.status = MIGRATION_CHECK.GOOD;
            this.migration = migration;
            this.version = version;
            this.host = host;
            this.db = db;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(null).
                    append("status", status).
                    append("message", message).
                    append("migration", migration != null ? migration.getSimpleName() : null).
                    append("version", version).
                    append("host", host).
                    append("db", db).
                    toString();
        }
    }
}
