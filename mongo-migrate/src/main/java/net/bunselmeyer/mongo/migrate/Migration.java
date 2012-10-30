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

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;

public abstract class Migration {

    abstract public void up(DB db);

    abstract public void down(DB db);

    protected void renameField(DB db, String collectionName, String from, String to) {
        DBCollection collection = db.getCollection(collectionName);
        DBObject query = new BasicDBObject(from, new BasicDBObject("$exists", true));
        DBCursor dbObjects = collection.find(query);
        for (DBObject dbObject : dbObjects) {
            dbObject.put(to, dbObject.get(from));
            dbObject.removeField(from);
            collection.save(dbObject);
        }
    }
}
