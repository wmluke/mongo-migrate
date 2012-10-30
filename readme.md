# mongo-migrate
`mongo-migrate` Maven plugin provides goals to perform mongo DB migrations.

## Pre-Alpha
I wanted to try my hand at writing maven plugin to support some of my various mongo hack-fun-projects.  So, please note that `mongo-migrate` is
incredibly raw and pre-alpha.  Any and all feedback is welcome.

## Installation

Commandline:

```
$ git clone https://github.com/wmluke/mongo-migrate.git
$ cd mongo-migrate
$ cd mvn install
```

Project POM:

```xml
<project>
    ...
    <dependencies>
        ...
        <dependency>
            <groupId>net.bunselmeyer</groupId>
            <artifactId>mongo-migrate</artifactId>
            <version>0.1-SNAPSHOT</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            ...
            <plugin>
                <groupId>net.bunselmeyer</groupId>
                <artifactId>mongo-maven-plugin</artifactId>
                <version>0.1-SNAPSHOT</version>
                <configuration>
                    <!-- Package to find Migrations -->
                    <package>net.bunselmeyer.mongo.migrate.migrations</package>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Java Usage

```java
@Connection(db = "blog", version = "2012-09-11T00:14:00-0800")
public class RenameUserEmailFieldMigration extends Migration {

    @Override
    public void up(DB db) {
        renameField(db, "User", "emailAddress", "email");
    }

    @Override
    public void down(DB db) {
        renameField(db, "User", "email", "emailAddress");
    }
}
```

## Maven Goals

### mongo:migrate
Migrate a mongo DB upwards.

```
$ mvn mongo:migrate
```