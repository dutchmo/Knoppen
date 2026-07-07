# Knoppen (SQL Generator Maven Plugin)

A CLI/Maven plugin that reads YAML configuration files and generates SQL insert/update statements from datafiles.

Similar in concept to Swagger/OpenAPI generation of service artifact generation, but generates SQL. 

1. reads a YAML configuration file, 
2. Validates the file against a JSON schema
3. Reads JSON/YAML/CSV datafiles
4. generates SQL insert/update statements from data

Operations in the plugin are tied to appropriate Maven lifecycle events.

- 
## Features

- ✅ Integration with Maven lifecycle
- ✅ Comprehensive unit tests
- ✅ CI/CD with GitHub Actions
- ✅ Supports JSON/YAML/CSV datafiles
- ✅ Supports multiple datafiles per table
- ✅ Upsert SQL generation
- ✅ Datafile validation against schema
- ✅ Validate/Generate/Record Count


### Supported Database Features
- Natural and Surrogate keys
- Foreign Keys
- Column Defaults
- On conflict strategies
- Column values derived from generators

## CLI Usage

### Validation
```bash
  knoppen validate schemas/blog.yaml
```

### Generation
```bash
  knoppen generate schemas/blog.yaml --no-strict --root-data-path /tmp/data --root-output-path /tmp --output-format LEGACY
```

### Maven Configuration

Add to your `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.austindroids</groupId>
      <artifactId>knoppen-maven-plugin</artifactId>
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
  </plugins>
</build>
```

## [Tutorial](doc/Tutorial.md)

