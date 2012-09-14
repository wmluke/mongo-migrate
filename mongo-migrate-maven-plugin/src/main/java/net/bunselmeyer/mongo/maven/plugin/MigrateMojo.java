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
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableListMultimap;
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

    public void execute() throws MojoExecutionException {

        if (StringUtils.isBlank(host)) {
            host = "localhost";
        }

        ConfigurationBuilder configuration = new ConfigurationBuilder() //
            .addUrls(Sets.newHashSet(buildOutputDirectoryUrl())) //
            .addClassLoader(buildProjectClassLoader());

        if (StringUtils.isNotBlank(migrationPackage)) {
            FilterBuilder filterBuilder = new FilterBuilder();
            filterBuilder.include(FilterBuilder.prefix(migrationPackage));
            configuration.filterInputsBy(filterBuilder);
        }

        Reflections reflections = new Reflections(configuration);
        Set<Class<? extends AbstractMigration>> allMigrations = reflections.getSubTypesOf(AbstractMigration.class);

        Set<Class<? extends AbstractMigration>> connectedMigrations = Sets.filter(allMigrations, new Predicate<Class<? extends AbstractMigration>>() {
            public boolean apply(Class<? extends AbstractMigration> input) {
                if (input == null) {
                    return false;
                }
                Connection connection = input.getAnnotation(Connection.class);
                if (connection == null || StringUtils.isBlank(connection.db()) || StringUtils.isBlank(connection.version())) {
                    return false;
                }
                try {
                    return DateTime.parse(connection.version()) != null;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        getLog().info("Found " + connectedMigrations.size() + " migrations.");

        ImmutableListMultimap<String, Class<? extends AbstractMigration>> index = buildIndex(connectedMigrations);

        for (String connectionDef : index.keySet()) {
            runMigrations(connectionDef, Lists.newArrayList(index.get(connectionDef)));
        }
    }

    private void runMigrations(String connectionDef, List<Class<? extends AbstractMigration>> migrations) {
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

        Collections.sort(migrations, new Comparator<Class<? extends AbstractMigration>>() {
            public int compare(Class<? extends AbstractMigration> o1, Class<? extends AbstractMigration> o2) {
                Connection a1 = o1.getAnnotation(Connection.class);
                Connection a2 = o2.getAnnotation(Connection.class);
                DateTime v1 = DateTime.parse(a1.version());
                DateTime v2 = DateTime.parse(a2.version());
                return v1.compareTo(v2);
            }
        });

        DateTime version;

        Class<? extends Migration> lastMigration = null;
        try {
            for (Class<? extends Migration> migration : migrations) {
                lastMigration = migration;
                Migration m = migration.newInstance();
                version = m.version();
                m.up(mongoDB);
                getLog().info("    " + migration.getName() + ", v" + version + " migration complete");
            }
        } catch (Exception e) {
            String name = lastMigration != null ? lastMigration.getName() : "";
            getLog().info("    FAIL! " + name + " migration error", e);
        }
    }

    private ImmutableListMultimap<String, Class<? extends AbstractMigration>> buildIndex(Set<Class<? extends AbstractMigration>> migrations) {
        return Multimaps.index(migrations, new Function<Class<? extends Migration>, String>() {
            public String apply(Class<? extends Migration> input) {
                Connection connection = input.getAnnotation(Connection.class);
                if (connection == null) {
                    getLog().info(input.getSimpleName() + " needs @Connection");
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
}
