package com.example.discount.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        Schema<?> promotionSchema = new ObjectSchema()
                .addProperty("promotionId", new StringSchema().example("P001"))
                .addProperty("promotionName", new StringSchema().example("Employee Discount 50%"))
                .addProperty("promotionBaseType", new StringSchema().example("SubscriptionPromotion"))
                .addProperty("discountCategory", new StringSchema().example("EmployeeDiscount"));

        Schema<?> inputSchema = new ObjectSchema()
                .addProperty("EligiblePromotions", new ArraySchema().items(promotionSchema));

        Schema<?> allowedSchema = new ObjectSchema()
                .addProperty("promotionId", new StringSchema())
                .addProperty("promotionName", new StringSchema())
                .addProperty("discountCategory", new StringSchema())
                .addProperty("appliedOrder", new IntegerSchema());

        Schema<?> rejectedSchema = new ObjectSchema()
                .addProperty("promotionId", new StringSchema())
                .addProperty("promotionName", new StringSchema())
                .addProperty("discountCategory", new StringSchema())
                .addProperty("reason", new StringSchema());

        Schema<?> resultSchema = new ObjectSchema()
                .addProperty("CompatibilityResult", new ObjectSchema()
                        .addProperty("AllowedPromotions", new ArraySchema().items(allowedSchema))
                        .addProperty("RejectedPromotions", new ArraySchema().items(rejectedSchema)));

        io.swagger.v3.oas.models.Operation postOp = new io.swagger.v3.oas.models.Operation()
                .summary("Evaluate promotion compatibility")
                .description("Evaluates which promotions are compatible and returns allowed/rejected promotions")
                .requestBody(new RequestBody()
                        .required(true)
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(inputSchema)
                                        .example(Map.of("EligiblePromotions", java.util.List.of(
                                                Map.of("promotionId", "P001", "promotionName", "Employee Discount 50%",
                                                        "promotionBaseType", "SubscriptionPromotion", "discountCategory", "EmployeeDiscount"),
                                                Map.of("promotionId", "P002", "promotionName", "Binding Discount 24mo",
                                                        "promotionBaseType", "SubscriptionPromotion", "discountCategory", "LegacyBindingDiscount")))))))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("Successful evaluation")
                                .content(new Content().addMediaType("application/json",
                                        new MediaType().schema(resultSchema)))));

        return new OpenAPI()
                .info(new Info()
                        .title("Discount Rules DMN API")
                        .version("1.0.0")
                        .description("Drools DMN decision table REST endpoints for promotion compatibility"))
                .paths(new Paths()
                        .addPathItem("/PromotionCompatibility", new PathItem().post(postOp)));
    }
}
