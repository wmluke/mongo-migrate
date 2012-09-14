package net.bunselmeyer.mongo.migrate;

import com.mongodb.DB;
import org.joda.time.DateTime;

public interface Migration {

    DateTime version();

    void up(DB db);

    void down(DB db);
}
