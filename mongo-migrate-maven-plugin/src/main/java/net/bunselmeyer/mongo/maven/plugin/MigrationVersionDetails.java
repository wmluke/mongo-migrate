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
