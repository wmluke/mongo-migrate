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
