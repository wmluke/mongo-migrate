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
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.mongodb.DB;
import com.mongodb.Mongo;
import net.bunselmeyer.mongo.annotations.Connection;
import net.bunselmeyer.mongo.migrate.AbstractMigration;
import net.bunselmeyer.mongo.migrate.Migration;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.jfrog.jade.plugins.common.injectable.MvnInjectableMojoSupport;
import org.jfrog.maven.annomojo.annotations.MojoGoal;
import org.jfrog.maven.annomojo.annotations.MojoParameter;
import org.jfrog.maven.annomojo.annotations.MojoPhase;
import org.jfrog.maven.annomojo.annotations.MojoRequiresDependencyCollection;
import org.jfrog.maven.annomojo.annotations.MojoRequiresDependencyResolution;
import org.joda.time.DateTime;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

/**
 *
 */
@MojoGoal("migrate")
@MojoPhase("process-classes")
@MojoRequiresDependencyResolution("test")
@MojoRequiresDependencyCollection("test")
public class MigrateMojo extends MvnInjectableMojoSupport {

    @MojoParameter(alias = "package", description = "Package containing migrations.")
    private String migrationPackage;

    @MojoParameter(description = "host of the mongo db. defaults to localhost.")
    private String host;

    private enum MIGRATION_CHECK {
        ERROR, WARNING, GOOD
    }

    public void execute() throws MojoExecutionException {

        if (StringUtils.isBlank(host)) {
            host = "localhost";
        }

        Set<Class<? extends AbstractMigration>> allMigrations = scanProjectForMigrations();

        ImmutableListMultimap<MIGRATION_CHECK, MigrationPreCheckStatus> statusIndex = buildMigrationStatusIndex(allMigrations);

        ImmutableList<MigrationPreCheckStatus> errors = statusIndex.get(MIGRATION_CHECK.ERROR);
        if (!errors.isEmpty()) {
            getLog().error("Fail: Please correct the following issues...");
            for (MigrationPreCheckStatus error : errors) {
                getLog().error("    " + error.migration.getName() + ": " + error.message);
            }
            return;
        }

        ImmutableList<MigrationPreCheckStatus> warnings = statusIndex.get(MIGRATION_CHECK.WARNING);
        if (!warnings.isEmpty()) {
            getLog().warn("Warnings...");
            for (MigrationPreCheckStatus warning : warnings) {
                getLog().warn("    " + warning.migration.getName() + ": " + warning.message);
            }
        }

        ImmutableList<MigrationPreCheckStatus> goodMigrations = statusIndex.get(MIGRATION_CHECK.GOOD);

        getLog().info("Found " + goodMigrations.size() + " migrations.");

        ImmutableListMultimap<String, MigrationPreCheckStatus> index = buildIndex(goodMigrations);

        for (String connectionDef : index.keySet()) {
            runMigrations(connectionDef, Lists.newArrayList(index.get(connectionDef)));
        }
    }

    private Set<Class<? extends AbstractMigration>> scanProjectForMigrations() throws MojoExecutionException {
        ConfigurationBuilder configuration = new ConfigurationBuilder() //
            .addUrls(Sets.newHashSet(buildOutputDirectoryUrl())) //
            .addClassLoader(buildProjectClassLoader());

        if (StringUtils.isNotBlank(migrationPackage)) {
            FilterBuilder filterBuilder = new FilterBuilder();
            filterBuilder.include(FilterBuilder.prefix(migrationPackage));
            configuration.filterInputsBy(filterBuilder);
        }

        Reflections reflections = new Reflections(configuration);
        return reflections.getSubTypesOf(AbstractMigration.class);
    }

    private ImmutableListMultimap<MIGRATION_CHECK, MigrationPreCheckStatus> buildMigrationStatusIndex(
        Set<Class<? extends AbstractMigration>> allMigrations) {
        Iterable<MigrationPreCheckStatus> migrationStatus =
            Iterables.transform(allMigrations, new Function<Class<? extends AbstractMigration>, MigrationPreCheckStatus>() {
                public MigrationPreCheckStatus apply(Class<? extends AbstractMigration> input) {
                    if (input == null) {
                        return new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Failed to load migration from classloader.", input);
                    }
                    Connection connection = input.getAnnotation(Connection.class);
                    if (connection == null) {
                        return new MigrationPreCheckStatus(MIGRATION_CHECK.WARNING, "Migration does not have @Connection", input);
                    }

                    if (StringUtils.isBlank(connection.db())) {
                        return new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Empty db property in @Connection", input);
                    }

                    if (StringUtils.isBlank(connection.version())) {
                        return new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Empty version property in @Connection", input);
                    }

                    try {
                        return DateTime.parse(connection.version()) != null ? //
                            new MigrationPreCheckStatus(MIGRATION_CHECK.GOOD, "", input) : //
                            new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Failed to parse @version to timestamp in @Connection", input);
                    } catch (Exception e) {
                        new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Failed to parse @version to timestamp in @Connection", input);
                    }
                    return new MigrationPreCheckStatus(MIGRATION_CHECK.ERROR, "Unknown error", input);
                }
            });

        return Multimaps.index(migrationStatus, new Function<MigrationPreCheckStatus, MIGRATION_CHECK>() {
            public MIGRATION_CHECK apply(MigrationPreCheckStatus input) {
                return input.status;
            }
        });
    }

    private void runMigrations(String connectionDef, List<MigrationPreCheckStatus> migrations) {
        List<String> split = Lists.newArrayList(Splitter.on(",").omitEmptyStrings().trimResults().split(connectionDef));
        if (split.size() != 2) {
            getLog().error("Failed to resolve @Connection annotation for these migrations:");
            getLog().error(Joiner.on("\n  ").skipNulls().join(migrations));
            return;
        }

        String dbHost = split.get(0);
        String db = split.get(1);
        Mongo mongo = null;
        try {
            mongo = new Mongo(dbHost);
        } catch (UnknownHostException e) {
            getLog().error("Failed to connect to " + dbHost);
            return;
        }

        DB mongoDB = mongo.getDB(db);

        getLog().info("Running migrations. Host: " + dbHost + ". DB: " + db);

        Collections.sort(migrations, new Comparator<MigrationPreCheckStatus>() {
            public int compare(MigrationPreCheckStatus o1, MigrationPreCheckStatus o2) {
                Connection a1 = o1.migration.getAnnotation(Connection.class);
                Connection a2 = o2.migration.getAnnotation(Connection.class);
                DateTime v1 = DateTime.parse(a1.version());
                DateTime v2 = DateTime.parse(a2.version());
                return v1.compareTo(v2);
            }
        });

        DateTime version;

        Class<? extends Migration> lastMigration = null;
        try {
            for (MigrationPreCheckStatus migrationStatus : migrations) {
                lastMigration = migrationStatus.migration;
                Migration m = migrationStatus.migration.newInstance();
                version = m.version();
                m.up(mongoDB);
                getLog().info("    " + migrationStatus.migration.getName() + ", v" + version + " migration complete");
            }
        } catch (Exception e) {
            String name = lastMigration != null ? lastMigration.getName() : "";
            getLog().info("    FAIL! " + name + " migration error", e);
        }
    }

    private ImmutableListMultimap<String, MigrationPreCheckStatus> buildIndex(Iterable<MigrationPreCheckStatus> migrations) {
        return Multimaps.index(migrations, new Function<MigrationPreCheckStatus, String>() {
            public String apply(MigrationPreCheckStatus input) {
                Connection connection = input.migration.getAnnotation(Connection.class);
                if (connection == null) {
                    getLog().info(input.migration.getSimpleName() + " needs @Connection");
                    return "";
                }
                String dbHost = StringUtils.isBlank(connection.host()) ? host : connection.host();
                return dbHost + "," + connection.db();
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

    private static class MigrationPreCheckStatus {
        public MIGRATION_CHECK status;
        public String message;
        public Class<? extends AbstractMigration> migration;

        private MigrationPreCheckStatus(MIGRATION_CHECK status, String message, Class<? extends AbstractMigration> migration) {
            this.status = status;
            this.message = message;
            this.migration = migration;
        }
    }
}
