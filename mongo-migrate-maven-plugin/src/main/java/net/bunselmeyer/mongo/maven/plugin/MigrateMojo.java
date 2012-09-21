package net.bunselmeyer.mongo.maven.plugin;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.mongodb.DB;
import com.mongodb.Mongo;
import com.mongodb.WriteConcern;
import net.bunselmeyer.mongo.annotations.Connection;
import net.bunselmeyer.mongo.migrate.Migration;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.jfrog.jade.plugins.common.injectable.MvnInjectableMojoSupport;
import org.jfrog.maven.annomojo.annotations.MojoExecute;
import org.jfrog.maven.annomojo.annotations.MojoGoal;
import org.jfrog.maven.annomojo.annotations.MojoParameter;
import org.jfrog.maven.annomojo.annotations.MojoRequiresDependencyCollection;
import org.jfrog.maven.annomojo.annotations.MojoRequiresDependencyResolution;
import org.joda.time.DateTime;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

/**
 * Maven mojo for running mongo migrations
 * Usage:
 * mvn mongo:migrate
 */
@MojoGoal("migrate")
@MojoExecute(phase = "process-classes")
@MojoRequiresDependencyResolution("test")
@MojoRequiresDependencyCollection("test")
public class MigrateMojo extends MvnInjectableMojoSupport {

    @MojoParameter(alias = "package", description = "Package containing migrations.")
    private String _migrationPackage;

    @MojoParameter(description = "host of the mongo db. defaults to localhost.")
    private String _host;

    protected enum MIGRATION_CHECK {
        ERROR, WARNING, GOOD
    }

    public void execute() throws MojoExecutionException {

        if (StringUtils.isBlank(_host)) {
            _host = "localhost";
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

        if (StringUtils.isNotBlank(_migrationPackage)) {
            FilterBuilder filterBuilder = new FilterBuilder();
            filterBuilder.include(FilterBuilder.prefix(_migrationPackage));
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
                    String host = StringUtils.isNotBlank(connection.host()) ? connection.host() : _host;
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
            mongo = new Mongo(migrationDetails.host);
        } catch (UnknownHostException e) {
            getLog().error("Failed to connect to " + migrationDetails.host);
            return;
        }

        mongo.setWriteConcern(WriteConcern.SAFE);

        DB db = mongo.getDB(migrationDetails.db);

        getLog().info("Running migrations. Host: " + migrationDetails.host + ". DB: " + migrationDetails.db);

        sortMigrationDetails(migrations);

        Class<? extends Migration> lastMigration = null;
        try {
            for (MigrationDetails migrationStatus : migrations) {
                lastMigration = migrationStatus.migration;
                Migration m = migrationStatus.migration.newInstance();
                m.up(db);
                getLog().info("    " + migrationStatus.migration.getName() + ", v" + migrationStatus.version + " migration complete");
            }
        } catch (Exception e) {
            String name = lastMigration != null ? lastMigration.getName() : "";
            getLog().info("    FAIL! " + name + " migration error", e);
        } finally {
            mongo.close();
        }
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
