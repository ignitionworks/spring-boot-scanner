# Spring Boot Scanner (SBS)

Spring Boot Scanner (SBS) is a tool for extracting Java and Spring Boot versions from apps deployed in CF or K8s

## Pre-requisites
* CF CLI ([here](https://docs.cloudfoundry.org/cf-cli/install-go-cli.html))
* Java v17+ (when not using native)

IMPORTANT: The user should be logged into CF and with permissions to download droplets (`cf download-droplet`, see: [here](https://cli.cloudfoundry.org/es-ES/v8/download-droplet.html))

## Download and execute

Download the JAR file from [here](https://github.com/ignitionworks/spring-boot-scanner/releases/download/v0.1.0/spring-boot-scanner.jar) and execute:
```
java -jar spring-boot-scanner.jar
```

## Build & execute 

### JAR

```bash
./gradlew build
java -jar build/libs/spring-scanner-0.0.1-SNAPSHOT.jar
```

### Native

```bash
./gradlew nativeCompile
./build/native/nativeCompile/spring-scanner
```

IMPORTANT: The native image is broken at the moment.

## Configure

See [application.properties](src/main/resources/application.properties)
