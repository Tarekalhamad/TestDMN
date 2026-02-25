package com.example.discount;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscountRulesTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    // --- Test 1: Two incompatible categories, higher precedence wins ---

    @Test
    void employeeVsBinding_employeeShouldWin() {
        String body = """
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
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P001"))
            .body("CompatibilityResult.AllowedPromotions[0].discountCategory", is("EmployeeDiscount"))
            .body("CompatibilityResult.AllowedPromotions[0].appliedOrder", is(1))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("P002"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("EmployeeDiscount"));
    }

    // --- Test 2: Two compatible categories, both survive ---

    @Test
    void geoAndKombo_bothShouldSurvive() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "P010",
                  "promotionName": "Geo Discount Stockholm",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "GeoDiscount"
                },
                {
                  "promotionId": "P011",
                  "promotionName": "Kombo Bundle Discount",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "KomboDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(2))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P010"))
            .body("CompatibilityResult.AllowedPromotions[0].appliedOrder", is(1))
            .body("CompatibilityResult.AllowedPromotions[1].promotionId", is("P011"))
            .body("CompatibilityResult.AllowedPromotions[1].appliedOrder", is(2))
            .body("CompatibilityResult.RejectedPromotions", hasSize(0));
    }

    // --- Test 3: Three-way conflict, only highest precedence survives ---

    @Test
    void employeeVsOtsVsBinding_onlyEmployeeSurvives() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "P020",
                  "promotionName": "OTS Discount 200kr",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "OTSDiscount"
                },
                {
                  "promotionId": "P021",
                  "promotionName": "Employee Discount",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "EmployeeDiscount"
                },
                {
                  "promotionId": "P022",
                  "promotionName": "Binding Discount",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "LegacyBindingDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P021"))
            .body("CompatibilityResult.AllowedPromotions[0].discountCategory", is("EmployeeDiscount"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(2));
    }

    // --- Test 4: Individual block by category ---

    @Test
    void promotionRule_promoBlockedWhenGroupPresent() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "PROMO-ATL-001",
                  "promotionName": "ATL Campaign Special",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "PromotionOffering"
                },
                {
                  "promotionId": "P031",
                  "promotionName": "Employee Discount",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "EmployeeDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P031"))
            .body("CompatibilityResult.AllowedPromotions[0].discountCategory", is("EmployeeDiscount"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("PROMO-ATL-001"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("Blocked by rule"));
    }

    // --- Test 5: Single promotion passes through unchanged ---

    @Test
    void singlePromotion_shouldPassThrough() {
        String body = """
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
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P040"))
            .body("CompatibilityResult.AllowedPromotions[0].appliedOrder", is(1))
            .body("CompatibilityResult.RejectedPromotions", hasSize(0));
    }

    // --- Test 6: Empty input returns empty output ---

    @Test
    void emptyInput_shouldReturnEmptyList() {
        String body = """
            {
              "EligiblePromotions": []
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(0))
            .body("CompatibilityResult.RejectedPromotions", hasSize(0));
    }

    // --- Test 7: Binding vs HW subscription, binding wins by precedence ---

    @Test
    void bindingVsHwSubscription_bindingShouldWin() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "P050",
                  "promotionName": "HW Subscription Discount",
                  "promotionBaseType": "HWSubscriptionPromotion",
                  "discountCategory": "HWSubscriptionDiscount"
                },
                {
                  "promotionId": "P051",
                  "promotionName": "Binding Discount 24mo",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "LegacyBindingDiscount"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("P051"))
            .body("CompatibilityResult.AllowedPromotions[0].discountCategory", is("LegacyBindingDiscount"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("P050"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("LegacyBindingDiscount"));
    }

    // --- Test 8: Explicit winner - PROMO-WEST-01 wins over PROMO-EAST-01 ---

    @Test
    void explicitWinner_westWinsOverEast() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "PROMO-EAST-01",
                  "promotionName": "East Region Campaign",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "PromotionOffering"
                },
                {
                  "promotionId": "PROMO-WEST-01",
                  "promotionName": "West Region Campaign",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "PromotionOffering"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("PROMO-WEST-01"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("PROMO-EAST-01"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("Blocked by rule"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("East and West"));
    }

    // --- Test 9: BY_PRECEDENCE winner - OTSDiscount (rank 8) beats PromotionOffering (rank 15) ---

    @Test
    void byPrecedenceWinner_higherPrecedenceWins() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "PROMO-BAS-01",
                  "promotionName": "Basic Offer",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "OTSDiscount"
                },
                {
                  "promotionId": "PROMO-PREM-01",
                  "promotionName": "Premium Offer",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "PromotionOffering"
                }
              ]
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/PromotionCompatibility")
        .then()
            .statusCode(200)
            .body("CompatibilityResult.AllowedPromotions", hasSize(1))
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("PROMO-BAS-01"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("PROMO-PREM-01"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("Blocked by rule"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("Basic and Premium"));
    }
}
