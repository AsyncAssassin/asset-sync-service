package com.example.assetsync.codegen;

import java.nio.file.Path;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class JooqCodegenRunner {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String GENERATED_PACKAGE = "com.example.assetsync.infrastructure.persistence.jooq.generated";

    private JooqCodegenRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = Path.of(args[1]).toAbsolutePath().normalize();

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("asset_sync_jooq")
            .withUsername("asset_sync")
            .withPassword("asset_sync")) {

            postgres.start();
            migrate(projectDir, postgres);
            generate(postgres, outputDir);
        }
    }

    private static void migrate(Path projectDir, PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = DriverManager.getConnection(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        )) {
            liquibase.database.Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var resourceAccessor = new DirectoryResourceAccessor(projectDir.resolve("src/main/resources"));
                 var liquibase = new Liquibase(CHANGELOG, resourceAccessor, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void generate(PostgreSQLContainer<?> postgres, Path outputDir) throws Exception {
        Configuration configuration = new Configuration()
            .withJdbc(new Jdbc()
                .withDriver("org.postgresql.Driver")
                .withUrl(postgres.getJdbcUrl())
                .withUser(postgres.getUsername())
                .withPassword(postgres.getPassword()))
            .withGenerator(new Generator()
                .withName("org.jooq.codegen.KotlinGenerator")
                .withDatabase(new org.jooq.meta.jaxb.Database()
                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                    .withInputSchema("public")
                    .withExcludes("databasechangelog|databasechangeloglock"))
                .withGenerate(new Generate()
                    .withDeprecated(false)
                    .withRecords(true)
                    .withPojos(false)
                    .withDaos(false)
                    .withJavaTimeTypes(true)
                    .withFluentSetters(true)
                    .withKotlinNotNullRecordAttributes(true)
                    .withKotlinNotNullPojoAttributes(true)
                    .withKotlinNotNullInterfaceAttributes(true))
                .withTarget(new Target()
                    .withPackageName(GENERATED_PACKAGE)
                    .withDirectory(outputDir.toString())));

        GenerationTool.generate(configuration);
    }
}
