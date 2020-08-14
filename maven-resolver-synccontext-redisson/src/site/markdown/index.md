# Redisson Sync Context for Maven Resolver

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<span style="color: red; font-size: 16pt">***Note***: *This component is still considered to be experimental use with caution!*</span>

The Redisson Sync Context is a Redisson-based distributed locks factory for Maven Resolver on top
of Redis to provide a fast, concurrent-safe access from one or multiple Maven instances to the
same local Maven repository.

For further details about the factory read the [Javadoc](./apidocs/org/eclipse/aether/synccontext/RedissonSyncContextFactory.html).

## Open Issues/Notes

- It only works when dependency injection is used and not the bundled `AetherModule` or
  `ServiceLocator` (Maven uses dependency injection)
- It includes a lot of trace logging which partially will go way as soon as it has been stablilized
- Usage from plugins has not been tested yet
- The `furnace-maven-plugin` does not work this implementation because it uses `ServiceLocator` instead
  of dependency injection
- The installation process has not been streamlined yet

## Installation/Testing

- Clone Maven Resolver, switch to `MRESOLVER-131` branch and perform `mvn install`
- Clone Maven, switch to `master` branch, change the Resolver version in Maven's parent POM: `1.5.1-SNAPSHOT`
- Extract `apache-maven-3.7.0-SNAPSHOT-bin.tar.gz` to a location of your choice
- Modify `${maven.home}/bin/m2.conf` by adding `optionally ${maven.home}/lib/ext/redisson/*.jar`
  right after the `${maven.home}/conf/logging` line
- Add/modify the following entries in `${maven.home}/conf/logging/simplelogger.properties`:
      ```
      org.slf4j.simpleLogger.showDateTime=true
      org.slf4j.simpleLogger.showThreadName=true
      org.slf4j.simpleLogger.showShortLogName=true
      org.slf4j.simpleLogger.log.org.eclipse.aether=trace
      #org.slf4j.simpleLogger.log.org.redisson=debug
      #org.slf4j.simpleLogger.log.io.netty=debug
      ```
- Go back to Resolver and run `mvn dependency:copy-dependencies -pl maven-resolver-synccontext-redisson`
- Copy the following dependencies from `maven-resolver-synccontext-redisson/target/dependency`
  to `${maven.home}/lib/ext/redisson/`:
      ```
      ├── byte-buddy-1.10.7.jar
      ├── cache-api-1.0.0.jar
      ├── jackson-annotations-2.11.1.jar
      ├── jackson-core-2.11.1.jar
      ├── jackson-databind-2.11.1.jar
      ├── jackson-dataformat-yaml-2.11.1.jar
      ├── javax.annotation-api-1.3.2.jar
      ├── jboss-marshalling-2.0.9.Final.jar
      ├── jboss-marshalling-river-2.0.9.Final.jar
      ├── jodd-bean-5.0.13.jar
      ├── jodd-core-5.0.13.jar
      ├── maven-resolver-synccontext-redisson-1.5.1-SNAPSHOT.jar
      ├── netty-buffer-4.1.51.Final.jar
      ├── netty-codec-4.1.51.Final.jar
      ├── netty-codec-dns-4.1.51.Final.jar
      ├── netty-common-4.1.51.Final.jar
      ├── netty-handler-4.1.51.Final.jar
      ├── netty-resolver-4.1.51.Final.jar
      ├── netty-resolver-dns-4.1.51.Final.jar
      ├── netty-transport-4.1.51.Final.jar
      ├── reactive-streams-1.0.3.jar
      ├── reactor-core-3.3.4.RELEASE.jar
      ├── redisson-3.13.3.jar
      ├── rxjava-2.2.19.jar
      └── snakeyaml-1.26.jar
      ```
    Dependencies which are already bundled with Maven have been omitted.
- Start your Redis instance on a machine of your choice (ideally `localhost`)
- Now start a multithreaded Maven (`3.7.0-SNAPSHOT`) build on your project and you should see at least these lines:
      ```
      # This line does not appear for the default configuration
      2316 [main] [TRACE] RedissonSyncContextFactory - Reading Redisson config file from '${maven.home}/conf/maven-resolver-redisson.yaml'
      4626 [main] [TRACE] RedissonSyncContextFactory - Created Redisson client with id '1c8db59b-7939-4014-8506-ae841c74608c'
      35318 [main] [TRACE] RedissonSyncContextFactory - Shutting down Redisson client with id '1c8db59b-7939-4014-8506-ae841c74608c'
      ```

## Configuration Options

Option | Type | Description | Default Value
--- | --- | --- | --- | ---
`aether.syncContext.redisson.configFile` | String | Path to a Redisson configuration file in YAML format. Read [official documentation](https://github.com/redisson/redisson/wiki/2.-Configuration) for details. | `${maven.home}/conf/maven-resolver-redisson.yaml`
`aether.syncContext.redisson.discriminator` | String | A discriminator uniquely identifying a host and repo pair. If the generation of the default value fails, it will use `sha1('')`. | `sha1('${hostname:-localhost}:${maven.repo.local}')`

## Set Configuration from Apache Maven

To set one of the configuration options from above just use system variables.
