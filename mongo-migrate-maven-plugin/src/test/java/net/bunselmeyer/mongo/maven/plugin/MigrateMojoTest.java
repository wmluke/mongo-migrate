package net.bunselmeyer.mongo.maven.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.mongodb.DB;
import net.bunselmeyer.mongo.annotations.Connection;
import net.bunselmeyer.mongo.migrate.Migration;
import org.joda.time.DateTime;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MigrateMojoTest {

    @Test
    public void testSortMigrationDetails() throws Exception {
        MigrateMojo.MigrationDetails aADetails = create(AaMigration.class);
        MigrateMojo.MigrationDetails bbDetails = create(BbMigration.class);
        MigrateMojo.MigrationDetails ccDetails = create(CcMigration.class);
        MigrateMojo.MigrationDetails ddDetails = create(DdMigration.class);
        MigrateMojo.MigrationDetails eeDetails = create(EeMigration.class);

        List<MigrateMojo.MigrationDetails> migrations = new ArrayList<MigrateMojo.MigrationDetails>();
        migrations.add(ddDetails);
        migrations.add(bbDetails);
        migrations.add(aADetails);
        migrations.add(eeDetails);
        migrations.add(ccDetails);

        MigrateMojo migrateMojo = new MigrateMojo();
        migrateMojo.sortMigrationDetails(migrations);

        Iterator<MigrateMojo.MigrationDetails> iterator = migrations.iterator();
        assertEquals(eeDetails, iterator.next());
        assertEquals(ddDetails, iterator.next());
        assertEquals(ccDetails, iterator.next());
        assertEquals(bbDetails, iterator.next());
        assertEquals(aADetails, iterator.next());
    }

    @Test
    public void testBuildIndex() throws Exception {

        MigrateMojo.MigrationDetails aADetails = create(AaMigration.class);
        MigrateMojo.MigrationDetails bbDetails = create(BbMigration.class);
        MigrateMojo.MigrationDetails ccDetails = create(CcMigration.class);
        MigrateMojo.MigrationDetails ddDetails = create(DdMigration.class);
        MigrateMojo.MigrationDetails eeDetails = create(EeMigration.class);

        List<MigrateMojo.MigrationDetails> migrations = new ArrayList<MigrateMojo.MigrationDetails>();
        migrations.add(aADetails);
        migrations.add(bbDetails);
        migrations.add(ccDetails);
        migrations.add(ddDetails);
        migrations.add(eeDetails);

        MigrateMojo migrateMojo = new MigrateMojo();
        ImmutableListMultimap<String, MigrateMojo.MigrationDetails> groups = migrateMojo.buildIndex(migrations);

        List<String> keys = Lists.newArrayList(groups.keySet());
        Collections.sort(keys);
        assertEquals("mongo1,db1 | mongo1,db2 | mongo2,db1 | mongo2,db2", Joiner.on(" | ").join(keys));

        ImmutableList<MigrateMojo.MigrationDetails> m1d1 = groups.get("mongo1,db1");
        assertEquals(2, m1d1.size());
        assertTrue(m1d1.contains(aADetails));
        assertTrue(m1d1.contains(bbDetails));

        ImmutableList<MigrateMojo.MigrationDetails> m1d2 = groups.get("mongo1,db2");
        assertEquals(1, m1d2.size());
        assertTrue(m1d2.contains(ccDetails));

        ImmutableList<MigrateMojo.MigrationDetails> m2d1 = groups.get("mongo2,db1");
        assertEquals(1, m2d1.size());
        assertTrue(m2d1.contains(eeDetails));

        ImmutableList<MigrateMojo.MigrationDetails> m2d2 = groups.get("mongo2,db2");
        assertEquals(1, m2d2.size());
        assertTrue(m2d2.contains(ddDetails));
    }

    @Test
    public void testBuildMigrationStatusIndex() throws Exception {

        Set<Class<? extends Migration>> migrations = new HashSet<Class<? extends Migration>>();
        migrations.add(GoodMigration.class);
        migrations.add(BadVersionMigration.class);
        migrations.add(EmptyVersionMigration.class);
        migrations.add(EmptyDbMigration.class);
        migrations.add(ConnectionLessMigration.class);

        MigrateMojo migrateMojo = new MigrateMojo();
        ImmutableListMultimap<MigrateMojo.MIGRATION_CHECK, MigrateMojo.MigrationDetails> statusIndex = migrateMojo.buildStatusIndex(migrations);

        ImmutableList<MigrateMojo.MigrationDetails> good = statusIndex.get(MigrateMojo.MIGRATION_CHECK.GOOD);
        assertEquals(1, good.size());
        assertEquals("status=GOOD,message=<null>,migration=GoodMigration,version=2012-09-20T20:44:00.000-08:00,host=mongo1,db=db1,<null>",
            good.iterator().next().toString());

        ImmutableList<MigrateMojo.MigrationDetails> warnings = statusIndex.get(MigrateMojo.MIGRATION_CHECK.WARNING);
        assertEquals(1, warnings.size());
        assertEquals(
            "status=WARNING,message=Migration does not have @Connection,migration=ConnectionLessMigration,version=<null>,host=<null>,db=<null>,<null>",
            warnings.iterator().next().toString());

        ImmutableList<MigrateMojo.MigrationDetails> errors = statusIndex.get(MigrateMojo.MIGRATION_CHECK.ERROR);

        assertEquals(3, errors.size());
        for (MigrateMojo.MigrationDetails error : errors) {
            if (error.migration == BadVersionMigration.class) {
                assertEquals(
                    "status=ERROR,message=Failed to parse @version to timestamp in @Connection,migration=BadVersionMigration,version=<null>,host=<null>,db=<null>,<null>",
                    error.toString());
            }
            if (error.migration == EmptyVersionMigration.class) {
                assertEquals(
                    "status=ERROR,message=Empty version property in @Connection,migration=EmptyVersionMigration,version=<null>,host=<null>,db=<null>,<null>",
                    error.toString());
            }
            if (error.migration == EmptyDbMigration.class) {
                assertEquals(
                    "status=ERROR,message=Empty db property in @Connection,migration=EmptyDbMigration,version=<null>,host=<null>,db=<null>,<null>",
                    error.toString());
            }
        }
    }

    private MigrateMojo.MigrationDetails create(Class<? extends Migration> type) {
        Connection connection = type.getAnnotation(Connection.class);
        DateTime version = DateTime.parse(connection.version());
        return new MigrateMojo.MigrationDetails(type, version, connection.host(), connection.db());
    }

    @Connection(host = "mongo1", db = "db1", version = "2012-09-20T06:44:00-0800")
    private static class AaMigration extends BaseTestMigration {

    }

    @Connection(host = "mongo1", db = "db1", version = "2012-09-20T05:44:00-0800")
    private static class BbMigration extends BaseTestMigration {

    }

    @Connection(host = "mongo1", db = "db2", version = "2012-09-20T04:44:00-0800")
    private static class CcMigration extends BaseTestMigration {

    }

    @Connection(host = "mongo2", db = "db2", version = "2012-09-20T03:44:00-0800")
    private static class DdMigration extends BaseTestMigration {

    }

    @Connection(host = "mongo2", db = "db1", version = "2012-09-20T02:44:00-0800")
    private static class EeMigration extends BaseTestMigration {

    }

    @Connection(host = "mongo1", db = "db1", version = "2012-09-20T20:44:00-0800")
    private static class GoodMigration extends BaseTestMigration {

    }

    @Connection(db = "db1", version = "not an iso timestamp")
    private static class BadVersionMigration extends BaseTestMigration {

    }

    @Connection(db = "aa", version = "")
    private static class EmptyVersionMigration extends BaseTestMigration {

    }

    @Connection(db = "", version = "2012-09-20T20:44:00-0800")
    private static class EmptyDbMigration extends BaseTestMigration {

    }

    private static class ConnectionLessMigration extends BaseTestMigration {

    }

    private static class BaseTestMigration extends Migration {

        public void up(DB db) {

        }

        public void down(DB db) {

        }
    }
}
