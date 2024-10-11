package ru.karpo.testcontainers;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.awaitility.Durations;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.vault.VaultContainer;

import static org.hamcrest.Matchers.is;

@RunWith(BlockJUnit4ClassRunner.class)
public class VaultKeyValueTest {

    private static final String VAULT_TOKEN = "123456";
    private static final Logger dockerLogger = LoggerFactory.getLogger("vault");

    static Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(dockerLogger);

    public static VaultContainer<?> vaultContainer = new VaultContainer<>("hashicorp/vault:1.13")
            .withVaultToken(VAULT_TOKEN)
            .withStartupTimeout(Durations.FIVE_SECONDS)
            .withEnv("VAULT_LOG_LEVEL", "debug")
            .withAccessToHost(true)
            //Включение key-val secrets engine в Vault
            //аналогично вызову vault secrets enable database через CLI
            .withInitCommand(
                    "secrets enable transit",
                    "write -f transit/keys/my-key",
                    "kv put secret/testing1 top_secret=password123",
                    "kv put secret/testing2 secret_one=password1 secret_two=password2 secret_three=password3 secret_four=password4");

    @BeforeClass
    public static void before(){
        vaultContainer.start();
        vaultContainer.followOutput(logConsumer);
    }

    @AfterClass
    public static void stop() {
        vaultContainer.stop();
    }

    @Test
    public void test() {
        ValidatableResponse response = RestAssured.given()
                .header("X-Vault-Token", VAULT_TOKEN)
                .and().log().all(true)
                .when()
                .get(vaultContainer.getHttpHostAddress() + "/v1/secret/data/testing1")
                .then().log().all(true)
                .and().body("data.data.top_secret", is("password123"));
    }
}
