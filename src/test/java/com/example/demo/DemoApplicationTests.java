package com.example.demo;

import com.example.demo.repository.EmployeeRepository;
import io.restassured.RestAssured;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Демонстрирует использование Test containers в тестах по работе с БД.
 * В рамках теста поднимается контейнер с Postgres
 * <p>
 * <p>
 * Перед тестом убедитесь, что у вас установлен Docker, и запустите скрипт docker-build.sh в тестовых ресурсах,
 * который соберет локальный образ postgres-tls
 *
 * @author alexeykarpo
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DemoApplicationTests {

    static DockerImageName myImage = DockerImageName.parse("postgres-tls:latest")
            .asCompatibleSubstituteFor("postgres");

    private static final String CLIENT_CERT_PATH = "src/test/resources/docker/certs/client.crt";
    private static final String CLIENT_KEY_PATH = "src/test/resources/docker/certs/client.key";
    private static final String ROOT_CERT_PATH = "src/test/resources/docker/certs/root.crt";

//    private static TemporaryFolder commonFolder = new TemporaryFolder();

    @ClassRule
    public static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>(myImage)
            .withDatabaseName("testdb")
            .withUsername("postgres")
            .withPassword("postgres123")
            //ssl params
            .withUrlParam("ssl", "true")
            //данная настройка определяет тип валидации: full означает,
            //что проверка будет учитывать соответствие сертифката hostname'у
            .withUrlParam("sslmode", "verify-ca")

            .withUrlParam("sslfactory", "org.postgresql.ssl.NonValidatingFactory")
            .withUrlParam("sslrootcert", ROOT_CERT_PATH)
            .withUrlParam("sslcert", CLIENT_CERT_PATH)
            .withUrlParam("sslkey", CLIENT_KEY_PATH)
            .withCommand("-c", "ssl=on",
                    "-c", "ssl_cert_file=/var/lib/postgresql/server.crt",
                    "-c", "ssl_key_file=/var/lib/postgresql/server.key",
                    "-c", "ssl_ca_file=/var/lib/postgresql/root.crt",
                    "-c", "log_destination=stderr",
                    "-c", "logging_collector=off")

            .withUrlParam("loggerLevel", "ON")
            .withInitScript("db/init.sql");

//    public static RuleChain ruleChain = RuleChain.outerRule(commonFolder).around(postgresContainer);

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
    public static void init() {
        postgresContainer.start();
    }

    @AfterClass
    public static void stop() {
        postgresContainer.stop();
    }

    private String getRootUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @Test
    public void shouldGetAllEmployees() {
        var employees = repository.findAll();
        Assert.assertEquals(2, employees.size());

        RestAssured.given()
                .when()
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
