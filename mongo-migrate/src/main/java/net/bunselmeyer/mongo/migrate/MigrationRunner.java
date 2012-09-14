package net.bunselmeyer.mongo.migrate;

import com.mongodb.DB;

public class MigrationRunner {

    private final DB _db;

    MigrationRunner(DB db) {
        _db = db;
    }

    public void migrate() {

    }

    public void migrate(Class<? extends Migration> migration) {
        try {
            migration.newInstance().up(_db);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback(Class<? extends Migration> migration) {
        try {
            migration.newInstance().down(_db);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void rollback(int steps) {

    }
}
