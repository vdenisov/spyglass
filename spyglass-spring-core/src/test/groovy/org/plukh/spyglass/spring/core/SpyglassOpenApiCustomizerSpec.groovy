package org.plukh.spyglass.spring.core

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import spock.lang.Specification

class SpyglassOpenApiCustomizerSpec extends Specification {

    def customizer = new SpyglassOpenApiCustomizer("demo-service")

    def "adds the generic x-service-name info extension and the Authorization security scheme"() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer.customise(openApi)

        then:
        openApi.info.extensions["x-service-name"] == "demo-service"
        openApi.components.securitySchemes["Authorization Header"].name == "Authorization"
        openApi.security*.keySet().flatten().contains("Authorization Header")
    }

    def "defaults the title to '<service> API' when none is set"() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "demo-service API"
    }

    def "treats springdoc's placeholder title as unset and replaces it"() {
        given: "springdoc pre-fills this default when no title is configured"
        def openApi = new OpenAPI().info(new Info().title("OpenAPI definition"))

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "demo-service API"
    }

    def "preserves a title the consumer set deliberately"() {
        given:
        def openApi = new OpenAPI().info(new Info().title("My Custom API"))

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "My Custom API"
    }
}
