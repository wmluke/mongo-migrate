package net.bunselmeyer.mongo.migrate;

import java.net.UnknownHostException;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;

public class MigrationTest {

    private static Mongo _mongo;
    private static DB _db;

    @BeforeClass
    public static void setUpClass() throws UnknownHostException {
        _mongo = new Mongo("localhost");
        _db = _mongo.getDB("unittest_db");
    }

    @AfterClass
    public static void tearDownClass() {
        _db.dropDatabase();
        _mongo.close();
    }

    @Test
    public void testRenameField() throws Exception {

        DBCollection fooCollection = _db.getCollection("foo");
        fooCollection.insert(new BasicDBObject("aa", "11"));
        fooCollection.insert(new BasicDBObject("aa", "22"));
        fooCollection.insert(new BasicDBObject("aa", "33"));

        assertEquals(3, fooCollection.find(new BasicDBObject("aa", new BasicDBObject("$exists", true))).length());

        TestMigration migration = new TestMigration();
        migration.renameField(_db, "foo", "aa", "bb");

        assertEquals(0, fooCollection.find(new BasicDBObject("aa", new BasicDBObject("$exists", true))).length());
        assertEquals(3, fooCollection.find(new BasicDBObject("bb", new BasicDBObject("$exists", true))).length());
    }

    private static class TestMigration extends Migration {

        public void up(DB db) {

        }

        public void down(DB db) {

        }
    }
}
