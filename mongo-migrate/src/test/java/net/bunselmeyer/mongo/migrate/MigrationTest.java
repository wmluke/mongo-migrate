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
