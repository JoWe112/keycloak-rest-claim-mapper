# Deployment Guide

## Prerequisites

- Keycloak **26.x** (Quarkus distribution) installed at `/opt/keycloak`
- Running on **GraalVM JDK 21** (required for the Polyglot API used by `query.script`)
- Maven **3.9+** and Java **21** on the build machine

## Build

```bash
git clone https://github.com/JoWe112/keycloak-rest-claim-mapper.git kc-rest-claim-mapper
cd kc-rest-claim-mapper
mvn clean package -q
```

The output fat JAR is `target/kc-rest-claim-mapper-1.0.0.jar`.  
It contains all bundled dependencies (Apache HttpClient 5, Jackson, Jayway JSONPath) with
shaded package names to avoid classpath conflicts.

## Deploy

```bash
# 1. Copy to providers directory
cp target/kc-rest-claim-mapper-1.0.0.jar /opt/keycloak/providers/

# 2. Re-run the Keycloak build step (required after adding a new provider)
/opt/keycloak/bin/kc.sh build

# 3. Start Keycloak
/opt/keycloak/bin/kc.sh start
```

> **Note on containers**: If running Keycloak in Docker/Kubernetes, mount or COPY the JAR
> into `/opt/keycloak/providers/` in your image and run `kc.sh build` in the `RUN` layer
> before the entrypoint.

### Kubernetes / Helm example

```dockerfile
FROM quay.io/keycloak/keycloak:26.0.7 AS builder
COPY kc-rest-claim-mapper-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0.7
COPY --from=builder /opt/keycloak /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
```

## Verify Registration

After startup, check that the mapper is registered:

```bash
# Look for rest-claim-mapper in startup logs
grep "rest-claim-mapper" /opt/keycloak/data/log/keycloak.log

# Or via Admin API
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://keycloak.example.com/admin/realms/master/clients" | jq .
```

In the Admin Console:
- Navigate to any client → **Client Scopes** → dedicated scope → **Mappers → Add Mapper → By Configuration**
- The mapper **REST Attribute Enrichment** should appear in the list.

## GraalVM Requirement

Keycloak 26.x Quarkus ships on GraalVM JDK by default. The `org.graalvm.polyglot` package
is already on the classpath — no extra installation required.

If you are running on a **plain JDK** (non-GraalVM), you need to:

1. Change the `graalvm` dependency scope in `pom.xml` from `provided` to `compile`:
   ```xml
   <dependency>
       <groupId>org.graalvm.polyglot</groupId>
       <artifactId>polyglot</artifactId>
       <version>24.1.1</version>
       <scope>compile</scope>
   </dependency>
   ```
2. Also add the JavaScript language runtime:
   ```xml
   <dependency>
       <groupId>org.graalvm.polyglot</groupId>
       <artifactId>js</artifactId>
       <version>24.1.1</version>
       <scope>compile</scope>
       <type>pom</type>
   </dependency>
   ```
3. Rebuild the fat JAR.

## Upgrading

To upgrade the provider:

1. Replace the JAR in `/opt/keycloak/providers/`.
2. Run `/opt/keycloak/bin/kc.sh build` again.
3. Restart Keycloak.

Existing mapper configurations are stored in the Keycloak database and are preserved across upgrades.
