package net.bunselmeyer.mongo.maven.plugin;

import org.bson.types.ObjectId;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.joda.time.DateTime;

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MigrationVersionDetails {
    private ObjectId _id;
    private DateTime _version;
    private DateTime _run;
    private String _migrationName;

    public ObjectId getId() {
        return _id;
    }

    public void setId(ObjectId id) {
        _id = id;
    }

    public DateTime getVersion() {
        return _version;
    }

    public void setVersion(DateTime version) {
        _version = version;
    }

    public DateTime getRun() {
        return _run;
    }

    public void setRun(DateTime run) {
        _run = run;
    }

    public String getMigrationName() {
        return _migrationName;
    }

    public void setMigrationName(String migrationName) {
        _migrationName = migrationName;
    }
}
