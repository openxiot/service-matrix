package cc.openxiot.console.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class SiteResourceTest {
    @Test
    void testHelloEndpoint() {
        given()
          .when().get("/v1/site")
          .then()
             .statusCode(200)
             .body("success", is(true))
             .body("data", is("Hello from SiteResource"));
    }
}
