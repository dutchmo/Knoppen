# Knoppen (SQL Generator Maven Plugin)

A Maven plugin that reads YAML configuration files and generates SQL insert/update statements for database system data.


This project is a Maven plugin written in Kotlin and built with Kotlin Gradle DSL. Similar in concept to Swagger/OpenAPI generation of service artifact generation, but instead generates SQL. 

reads a YAML configuration file, 
Validates the file against a JSON schema
generates SQL insert/update statements from YAML/CSV files

Operations in the plugin are tied to appropriate Maven lifecycle events.


- Validation
- Plugin
- Sections
- Comments
- Natural and Surrogate keys
- Foreign Keys
- 
- Defaults
  Validate/Generate/Record Count
- 
## Features


- ✅ Integration with Maven lifecycle
- ✅ Comprehensive unit tests
- ✅ CI/CD with GitHub Actions

## Usage

```bash
db-seeder --schema schema.yaml \
          --data data1.yaml,data2.yaml \
          --output output.sql \
          --dialect postgres \
          --validate-only
```
### Basic Configuration

Add to your `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.yourcompany</groupId>
      <artifactId>bootstrap-sqlgen-maven-plugin</artifactId>
      <version>1.0.0</version>
      <configuration>
        <configFile>${project.basedir}/src/main/resources/bootstrap-config.yaml</configFile>
        <dialect>postgres</dialect>
        <validateOnly>false</validateOnly>
        <transactionMode>single|per-table|none</transactionMode>
        <includeDropStatements>false</includeDropStatements>
        <encoding>UTF-8</encoding>
        <failOnValidationError>true</failOnValidationError>
        <outputFileName>seed.sql</outputFileName>
        <foreignKeyValidation>true</foreignKeyValidation>
        <sequencePrefix>seq_</sequencePrefix>
      </configuration>
    </plugin>

```
