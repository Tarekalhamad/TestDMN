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

    // --- Test 4: Individual block by category (R1: promo 299 blocked by EmployeeDiscount) ---

    @Test
    void promotionRule_promoBlockedWhenGroupPresent() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "299",
                  "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "SignUpDiscount"
                },
                {
                  "promotionId": "103",
                  "promotionName": "Employee discount on mobile main sim",
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
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("103"))
            .body("CompatibilityResult.AllowedPromotions[0].discountCategory", is("EmployeeDiscount"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("299"))
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

    // --- Test 8: Explicit overrideWinner - promo 73 (internal GEO) wins over promo 72 (external GEO) ---

    @Test
    void explicitWinner_internalGeoWinsOverExternal() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "72",
                  "promotionName": "GEO rabatt external retail",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "GeoDiscount"
                },
                {
                  "promotionId": "73",
                  "promotionName": "GEO rabatt interna kanaler",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "GeoDiscount"
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
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("73"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("72"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("Blocked by rule"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("GEO"));
    }

    // --- Test 9: BY_PRECEDENCE overrideWinner - SignUpDiscount (rank 5) beats OTSDiscount (rank 8) ---
    // Note: OTSDiscount vs SignUpDiscount is also a CategoryRules conflict, so the category
    // rule fires first. The result is the same (272 rejected, 306 survives) but the rejection
    // reason comes from the category-level rule rather than the promotion-level rule.

    @Test
    void byPrecedenceWinner_higherPrecedenceWins() {
        String body = """
            {
              "EligiblePromotions": [
                {
                  "promotionId": "272",
                  "promotionName": "Discount OTS - New MPO 10, 30 GB and extra användare mobil",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "OTSDiscount"
                },
                {
                  "promotionId": "306",
                  "promotionName": "New or Existing MPO main or Extra användare Mobil with binding",
                  "promotionBaseType": "SubscriptionPromotion",
                  "discountCategory": "SignUpDiscount"
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
            .body("CompatibilityResult.AllowedPromotions[0].promotionId", is("306"))
            .body("CompatibilityResult.RejectedPromotions", hasSize(1))
            .body("CompatibilityResult.RejectedPromotions[0].promotionId", is("272"))
            .body("CompatibilityResult.RejectedPromotions[0].reason", containsString("SignUpDiscount"));
    }
}
