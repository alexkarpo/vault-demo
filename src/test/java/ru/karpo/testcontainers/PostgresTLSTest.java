package ru.karpo.testcontainers;

import io.restassured.RestAssured;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.shaded.org.awaitility.Durations;
import org.testcontainers.utility.DockerImageName;
import ru.karpo.testcontainers.repository.EmployeeRepository;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Демонстрация использования Testcontainers при работе с БД. В рамках теста поднимается контейнер с Postgres
 * <p>
 * Далее через вызов REST-контроллера извлекаются данные из БД.
 * JDBC-подключение клиентского приложение осуществляется по TLS
 * <p>
 * Перед тестом убедитесь, что у вас установлен Docker, и запустите скрипт src/test/resources/docker/docker-build.sh,
 * который соберет локальный образ БД "postgres-tls" с предварительными настройками для TLS-подключения
 *
 * @author alexeykarpo
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PostgresTLSTest {

    private static final Logger dockerLogger = LoggerFactory.getLogger("docker-output");

    static DockerImageName myImage = DockerImageName.parse("postgres-tls:latest")
            .asCompatibleSubstituteFor("postgres");

    private static final String CLIENT_CERT_PATH = "src/test/resources/docker/certs/client.crt";
    private static final String CLIENT_KEY_PATH = "src/test/resources/docker/certs/client.key";
    private static final String ROOT_CERT_PATH = "src/test/resources/docker/certs/root.crt";
    private static final String POSTGRES_TEST_DATABASE_NAME = "testdb";

    static Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(dockerLogger);

    public static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>(myImage)
            .withDatabaseName(POSTGRES_TEST_DATABASE_NAME)
            .withUsername("postgres")
            .withPassword("postgres123")

            //Формирование параметров JDBC URL для клиентского подключения к БД.
            //Сформированный URL можно получить посредством метода postgresContainer.getJdbcUrl()
            //Пример URL: postgresql://username:password@hostname:port/databasename?sslmode=verify-full&sslrootcert=/path/to/root.crt&sslcert=/path/to/client.crt&sslkey=/path/to/client.key
            .withUrlParam("ssl", "true")

            //Данный параметр URL определяет тип валидации на клиенте:
            //1. verify-ca:
            //   - Проверяет, выдан ли сертификат серверу доверенным центром сертификации (CA).
            //   - Не проверяет, совпадает ли имя хоста сервера с именем, указанным в сертификате.
            //   - Может быть использован, если важно только наличие действительного сертификата.
            //
            //2. verify-full:
            //   - Проверяет, выдан ли сертификат доверенным CA.
            //   - Проверяет также, что имя хоста сервера совпадает с именем, указанным в сертификате.
            //   - Обеспечивает более высокий уровень безопасности, так как гарантирует, что вы подключаетесь к ожидаемому серверу.
            .withUrlParam("sslmode", "verify-ca")
            .withUrlParam("sslfactory", "org.postgresql.ssl.NonValidatingFactory")
            .withUrlParam("sslrootcert", ROOT_CERT_PATH)
            .withUrlParam("sslcert", CLIENT_CERT_PATH)
            .withUrlParam("sslkey", CLIENT_KEY_PATH)
            .withUrlParam("loggerLevel", "ON")

            //CMD args для ENTRYPOINT-команды, задаваемой в Dockerfile
            //Выдержка из документации Postgres: https://www.postgresql.org/docs/current/ssl-tcp.html
            //
            //To start [database server] in SSL mode, files containing the server certificate and private key must exist.
            //By default, these files are expected to be named server.crt and server.key, respectively, in the server's data directory,
            //but other names and locations can be specified using the configuration parameters ssl_cert_file and ssl_key_file.
            .withCommand("-c", "ssl=on",
                    "-c", "ssl_cert_file=/var/lib/postgresql/server.crt",
                    "-c", "ssl_key_file=/var/lib/postgresql/server.key",
                    "-c", "ssl_ca_file=/var/lib/postgresql/root.crt",
                    "-c", "log_destination=stderr",
                    "-c", "logging_collector=off")

            //Скрипт, который наполняет базу
            .withInitScript("db/init.sql")
            .withStartupTimeout(Durations.ONE_SECOND);

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
    }

    @Autowired
    private EmployeeRepository repository;

    @LocalServerPort
    private int port;

    @BeforeClass
    public static void init() throws IOException, InterruptedException {
        postgresContainer.start();
        postgresContainer.followOutput(logConsumer);
    }

    @AfterClass
    public static void stop() {
        postgresContainer.stop();
    }

    private String getRootUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @Test
    public void shouldGetAllEmployeesFromDatabaseWhenDatabaseRunsOverSSLInContainer() {
        var employees = repository.findAll();
        Assert.assertEquals(2, employees.size());

        RestAssured.given()
                .and().log().all()
                .get(getRootUrl() + "/employees")
                .then()
                .log().all()
                .and()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].firstName", equalTo("John"))
                .body("[0].lastName", equalTo("Doe"))
                .body("[0].email", equalTo("john.doe@example.com"))
                .body("[1].firstName", equalTo("Jane"))
                .body("[1].lastName", equalTo("Smith"))
                .body("[1].email", equalTo("jane.smith@example.com"));
    }
}
