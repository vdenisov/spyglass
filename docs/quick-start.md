# Quick start

Spyglass needs three things in place: a springdoc starter generating an OpenAPI document, the matching
Spyglass adapter on the classpath, and one activation declaration. springdoc serves `/v3/api-docs`;
Spyglass serves the explorer at **`/apidocs`** and redirects `/` and `/apidocs` to it (a `302`).

## Add the dependency

Pick the adapter for your web stack. Each depends on `spyglass-core` (the front-end assets) and a
springdoc starter (`provided`, so your service's versions win).

**Servlet (Spring MVC):**

```xml
<dependency>
  <groupId>org.plukh.spyglass</groupId>
  <artifactId>spyglass-spring-webmvc</artifactId>
  <version>1.3.0</version>
</dependency>
```

**Reactive (WebFlux):**

```xml
<dependency>
  <groupId>org.plukh.spyglass</groupId>
  <artifactId>spyglass-spring-webflux</artifactId>
  <version>1.3.0</version>
</dependency>
```

If your service does not already pull one, add the matching springdoc starter
(`springdoc-openapi-starter-webmvc-ui`/`-api` or `springdoc-openapi-starter-webflux-ui`/`-api`).

## Activation styles

### Explicit `@Import` (the supplied adapters)

Both adapters expose the same single entry point, `SpyglassConfiguration`, and ship **no**
`AutoConfiguration.imports` / `spring.factories` — nothing activates until you import it:

```java
// Servlet
@Configuration
@Import(org.plukh.spyglass.spring.webmvc.SpyglassConfiguration.class)
class ApiDocsConfig {}
```

```java
// Reactive
@Configuration
@Import(org.plukh.spyglass.spring.webflux.SpyglassConfiguration.class)
class ApiDocsConfig {}
```

`SpyglassConfiguration` wires the additive springdoc `OpenApiCustomizer` (security scheme + default
title + `x-service-name`) and the friendly redirects. The static assets are served by Spring from
`META-INF/resources/apidocs/` on classpath presence alone — no resource handler is registered.

### Auto-registration (a flavor shim)

A distribution can publish a thin shim that adds an `AutoConfiguration.imports` entry pointing at
`SpyglassConfiguration`, so its consumers get the explorer with **zero code** (classpath presence is
enough). An auto-registering extension wires this; the adapters here deliberately do not — see the
[adapter contract](adapter-contract.md).

## Verify

Start the service and open `http://localhost:8080/apidocs`. You should see your operations grouped by
tag in the sidebar. If the explorer loads but shows no operations, confirm `/v3/api-docs` returns your
document (or point Spyglass at a different path — see [configuration](configuration.md)).

## A runnable example

`spyglass-demo` is a complete, self-contained servlet example:

```bash
mvn -pl spyglass-demo -am clean install -DskipTests=true
mvn -pl spyglass-demo spring-boot:run
# http://localhost:8080/apidocs
```

It is the canonical consumer example the rest of these docs build on.
