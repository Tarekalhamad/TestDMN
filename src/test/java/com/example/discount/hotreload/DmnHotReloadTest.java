package com.example.discount.hotreload;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DmnHotReloadTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void getDmnContent_shouldReturnXml() {
        given()
        .when()
            .get("/api/dmn/content")
        .then()
            .statusCode(200)
            .contentType(ContentType.XML)
            .body(containsString("PromotionCompatibility"))
            .body(containsString("CategoryPrecedence"));
    }

    @Test
    void putDmnContent_withValidXml_shouldSucceed() {
        String currentXml = given()
            .when()
            .get("/api/dmn/content")
            .then()
            .statusCode(200)
            .extract().asString();

        given()
            .contentType(ContentType.XML)
            .body(currentXml)
        .when()
            .put("/api/dmn/content")
        .then()
            .statusCode(200)
            .body("status", is("success"));
    }

    @Test
    void putDmnContent_withInvalidXml_shouldReturn400() {
        given()
            .contentType(ContentType.XML)
            .body("<invalid>not a dmn</invalid>")
        .when()
            .put("/api/dmn/content")
        .then()
            .statusCode(400)
            .body("status", is("error"));
    }

    @Test
    void evaluate_singlePromotion_shouldPassThrough() {
        String evalBody = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "P040",
                  "promotionName": "OTS Discount 200kr",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "OTSDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(evalBody)
        .when()
            .post("/api/dmn/evaluate")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P040"));
    }

    @Test
    void hotReload_evaluationUsesUpdatedRules() {
        // Get current XML
        String currentXml = given()
            .when()
            .get("/api/dmn/content")
            .then()
            .statusCode(200)
            .extract().asString();

        // Save current (triggers hot-reload with same rules)
        given()
            .contentType(ContentType.XML)
            .body(currentXml)
        .when()
            .put("/api/dmn/content")
        .then()
            .statusCode(200);

        // Verify evaluation via hot-reloaded endpoint works correctly
        String evalBody = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "P001",
                  "promotionName": "Employee Discount 50%",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "EmployeeDiscount"
                },
                {
                  "promotionId": "P002",
                  "promotionName": "Binding Discount 24mo",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "LegacyBindingDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(evalBody)
        .when()
            .post("/api/dmn/evaluate")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P001"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("P002"));
    }

    @Test
    void rollback_shouldSucceed() {
        given()
        .when()
            .post("/api/dmn/rollback")
        .then()
            .statusCode(200)
            .body("status", is("success"));
    }

    @Test
    void getBackup_shouldReturnXml() {
        given()
        .when()
            .get("/api/dmn/backup")
        .then()
            .statusCode(200)
            .contentType(ContentType.XML)
            .body(containsString("PromotionCompatibility"));
    }
}
