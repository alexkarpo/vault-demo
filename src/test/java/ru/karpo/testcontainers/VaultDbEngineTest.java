package ru.karpo.testcontainers;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.shaded.org.awaitility.Durations;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.hamcrest.Matchers.not;

@RunWith(BlockJUnit4ClassRunner.class)
public class VaultDbEngineTest {

    private static final Logger logger = LoggerFactory.getLogger(VaultDbEngineTest.class);
    private static final Logger dockerLogger = LoggerFactory.getLogger("docker");
    private static final Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(dockerLogger);

    static DockerImageName myImage = DockerImageName.parse("postgres-tls:latest")
            .asCompatibleSubstituteFor("postgres");

    private static final String DATABASE_NAME = "testdb";
    private static final String VAULT_TOKEN = "123456";
    private static final String INIT_DB_USER = "postgres";
    private static final String INIT_DB_PASSWORD = "postgres123";

    public static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>(myImage)
            .withDatabaseName(DATABASE_NAME)
            .withUsername(INIT_DB_USER)
            .withPassword(INIT_DB_PASSWORD)
            .withCommand("-c", "log_destination=stderr", "-c", "logging_collector=off")

            //Скрипт, который наполняет базу
            .withInitScript("db/init.sql")
            .withStartupTimeout(Durations.ONE_SECOND)
            .withAccessToHost(true);

    public static VaultContainer<?> vaultContainer = new VaultContainer<>("hashicorp/vault:1.13")
            .withVaultToken(VAULT_TOKEN)
            .withStartupTimeout(Durations.FIVE_SECONDS)
            .withEnv("VAULT_LOG_LEVEL", "debug")
            .withAccessToHost(true)
            //Включение database secrets engine в Vault
            //аналогично вызову vault secrets enable database через CLI
            .withInitCommand("secrets enable database");

    /**
     * Перед запуском теста первым стартует контейнер с Postgres
     * <p>
     * Затем настраивается контейнер Vault. Для этого выполняется ряд команд через его CLI
     * <p>
     * Их перечень описан в документации для <a href="https://developer.hashicorp.com/vault/docs/secrets/databases/postgresql">PostgreSQL database secrets engine</a>
     */
    @BeforeClass
    public static void init() throws IOException, InterruptedException {
        postgresContainer.start();
        postgresContainer.followOutput(logConsumer);

        vaultContainer.start();
        vaultContainer.followOutput(logConsumer);

        logger.debug("JDBC URL: {}", postgresContainer.getJdbcUrl());

        var dbPort = postgresContainer.getFirstMappedPort();
        var url = String.format("connection_url=postgresql://{{username}}:{{password}}@host.docker.internal:%d/%s", dbPort, DATABASE_NAME);

        //конфигурация для подключения к БД
        Container.ExecResult execResult0 = vaultContainer.execInContainer("vault", "write", "database/config/" + DATABASE_NAME,
                "plugin_name=postgresql-database-plugin",
                "allowed_roles=my-role",
                url,
                "username=" + INIT_DB_USER,
                "password=" + INIT_DB_PASSWORD);

        logExecResult(execResult0);

        //Создание роли (пользователя) с логином и паролем, т.е. credentials, находящимся под управлением Vault
        Container.ExecResult execResult = vaultContainer.execInContainer("vault", "write", "database/roles/my-role",
                "db_name=" + DATABASE_NAME,
                "creation_statements=CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';GRANT SELECT, UPDATE, INSERT ON ALL TABLES IN SCHEMA public TO \"{{name}}\";GRANT USAGE,  SELECT ON ALL SEQUENCES IN SCHEMA public TO \"{{name}}\";",
                "default_ttl=1h",
                "max_ttl=24h");

        logExecResult(execResult);
    }

    @AfterClass
    public static void stop() {
        postgresContainer.stop();
        vaultContainer.stop();
    }

    private static void logExecResult(Container.ExecResult execResult) {
        if (execResult.getExitCode() == 0) {
            dockerLogger.debug("Command execution: {}", execResult.getStdout());
        } else {
            dockerLogger.debug("Command error execution: {}, {}", execResult.getStderr(), execResult.getStdout());
        }
    }

    @Test
    public void shouldConnectToDatabaseWithNewCredentialsWhenVaultRotatesPassword() throws IOException, InterruptedException, SQLException {
        //проверяем, что пароль для БД изменился
        ValidatableResponse response = RestAssured.given()
                .header("X-Vault-Token", VAULT_TOKEN)
                .and().log().all(true)
                .when()
                .get(vaultContainer.getHttpHostAddress() + "/v1/database/creds/my-role")
                .then().log().all(true)
                .body("data.password", not(INIT_DB_PASSWORD));

        var dbPort = postgresContainer.getFirstMappedPort();
        String url = String.format("jdbc:postgresql://localhost:%d/%s",dbPort, DATABASE_NAME);

        //принудительно ротируем изначальный cred логин/пароль в Vault
        var execResult = vaultContainer.execInContainer("vault", "write", "-force", "database/rotate-root/" + DATABASE_NAME);
        logExecResult(execResult);
        Assert.assertEquals(0, execResult.getExitCode());

        //проверяем, что подключение со старыми кредами не работает
        Assert.assertThrows(SQLException.class, () -> establishDbConnection(url, INIT_DB_USER, INIT_DB_PASSWORD));

        Map<String, String> data = response.extract().response().jsonPath().getMap("data");
        Assert.assertTrue(establishDbConnection(url, data.get("username"), data.get("password")));
    }

    private boolean establishDbConnection(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)){
            if (connection != null) {
                logger.info("Connection established!");
                return true;
            }
        }
        return false;
    }
}
