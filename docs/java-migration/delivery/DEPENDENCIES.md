# 依赖与许可证声明清单

自动依据：Maven实际resolved列表、本地artifact POM（可追溯父POM继承）及JAR NOTICE元数据、npm lock。完整坐标/hash/出处在 evidence/J5-04/dependencies.json。以下是声明核对，未代签再分发或法律兼容结论。

Maven 100项；npm lock 213项（含开发/可选/平台变体，不等于浏览器最终bundle组件数）。

## Maven resolved

| 坐标 | scope | 声明许可证 | 出处/状态 |
|---|---|---|---|
| ch.qos.logback:logback-classic:jar:1.5.38:compile | compile | EPL-2.0 / LGPL-2.1-only | inherited_parent_pom |
| ch.qos.logback:logback-core:jar:1.5.38:compile | compile | EPL-2.0 / LGPL-2.1-only | inherited_parent_pom |
| com.fasterxml.jackson.core:jackson-annotations:jar:2.21:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| com.fasterxml.jackson.core:jackson-core:jar:2.21.7:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| com.fasterxml.jackson.core:jackson-databind:jar:2.21.7:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| com.fasterxml:classmate:jar:1.7.3:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| com.jayway.jsonpath:json-path:jar:2.10.0:test | test | The Apache Software License, Version 2.0 | declared_in_pom |
| com.vaadin.external.google:android-json:jar:0.0.20131108.vaadin1:test | test | Apache License 2.0 | declared_in_pom |
| com.zaxxer:HikariCP:jar:7.0.2:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| commons-logging:commons-logging:jar:1.3.6:compile | compile | Apache-2.0 | inherited_parent_pom |
| io.micrometer:micrometer-commons:jar:1.16.8:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| io.micrometer:micrometer-core:jar:1.16.8:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| io.micrometer:micrometer-jakarta9:jar:1.16.8:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| io.micrometer:micrometer-observation:jar:1.16.8:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| jakarta.activation:jakarta.activation-api:jar:2.1.4:test | test | EDL 1.0 | declared_in_pom |
| jakarta.annotation:jakarta.annotation-api:jar:3.0.0:compile | compile | EPL 2.0 / GPL2 w/ CPE | declared_in_pom |
| jakarta.validation:jakarta.validation-api:jar:3.1.1:compile | compile | Apache License 2.0 | declared_in_pom |
| jakarta.xml.bind:jakarta.xml.bind-api:jar:4.0.5:test | test | Eclipse Distribution License - v 1.0 | inherited_parent_pom |
| net.bytebuddy:byte-buddy-agent:jar:1.17.8:test | test | Apache License, Version 2.0 | inherited_parent_pom |
| net.bytebuddy:byte-buddy:jar:1.17.8:test | test | Apache License, Version 2.0 | inherited_parent_pom |
| net.minidev:accessors-smart:jar:2.6.0:test | test | The Apache Software License, Version 2.0 | declared_in_pom |
| net.minidev:json-smart:jar:2.6.0:test | test | The Apache Software License, Version 2.0 | declared_in_pom |
| org.apache.logging.log4j:log4j-api:jar:2.25.5:compile | compile | Apache-2.0 | declared_in_pom |
| org.apache.logging.log4j:log4j-to-slf4j:jar:2.25.5:compile | compile | Apache-2.0 | declared_in_pom |
| org.apache.tomcat.embed:tomcat-embed-core:jar:11.0.27:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.apache.tomcat.embed:tomcat-embed-el:jar:11.0.27:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.apache.tomcat.embed:tomcat-embed-websocket:jar:11.0.27:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.apiguardian:apiguardian-api:jar:1.1.2:test | test | The Apache License, Version 2.0 | declared_in_pom |
| org.assertj:assertj-core:jar:3.27.7:test | test | Apache-2.0 | declared_in_pom |
| org.awaitility:awaitility:jar:4.3.0:test | test | Apache 2.0 | inherited_parent_pom |
| org.checkerframework:checker-qual:jar:3.55.1:runtime | runtime | The MIT License | declared_in_pom |
| org.flywaydb:flyway-core:jar:11.14.1:compile | compile | Apache License, Version 2.0 | inherited_parent_pom |
| org.flywaydb:flyway-database-postgresql:jar:11.14.1:compile | compile | Apache License, Version 2.0 | inherited_parent_pom |
| org.hamcrest:hamcrest:jar:3.0:test | test | BSD-3-Clause | declared_in_pom |
| org.hdrhistogram:HdrHistogram:jar:2.2.2:runtime | runtime | Public Domain, per Creative Commons CC0 / BSD-2-Clause | declared_in_pom |
| org.hibernate.validator:hibernate-validator:jar:9.0.2.Final:compile | compile | Apache License 2.0 | declared_in_pom |
| org.jboss.logging:jboss-logging:jar:3.6.3.Final:compile | compile | Apache License 2.0 | declared_in_pom |
| org.jspecify:jspecify:jar:1.0.1:compile | compile | The Apache License, Version 2.0 | declared_in_pom |
| org.junit.jupiter:junit-jupiter-api:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.junit.jupiter:junit-jupiter-engine:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.junit.jupiter:junit-jupiter-params:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.junit.jupiter:junit-jupiter:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.junit.platform:junit-platform-commons:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.junit.platform:junit-platform-engine:jar:6.0.3:test | test | Eclipse Public License v2.0 | declared_in_pom |
| org.latencyutils:LatencyUtils:jar:2.0.3:runtime | runtime | Public Domain, per Creative Commons CC0 | declared_in_pom |
| org.mockito:mockito-core:jar:5.20.0:test | test | MIT | declared_in_pom |
| org.mockito:mockito-junit-jupiter:jar:5.20.0:test | test | MIT | declared_in_pom |
| org.objenesis:objenesis:jar:3.3:test | test | Apache License, Version 2.0 | inherited_parent_pom |
| org.opentest4j:opentest4j:jar:1.3.0:test | test | The Apache License, Version 2.0 | declared_in_pom |
| org.ow2.asm:asm:jar:9.7.1:test | test | BSD-3-Clause | declared_in_pom |
| org.postgresql:postgresql:jar:42.7.14:runtime | runtime | BSD-2-Clause | declared_in_pom |
| org.skyscreamer:jsonassert:jar:1.5.3:test | test | The Apache Software License, Version 2.0 | declared_in_pom |
| org.slf4j:jul-to-slf4j:jar:2.0.20:compile | compile | MIT | inherited_parent_pom |
| org.slf4j:slf4j-api:jar:2.0.20:compile | compile | MIT | inherited_parent_pom |
| org.springframework.boot:spring-boot-actuator-autoconfigure:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-actuator:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-autoconfigure:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-health:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-http-converter:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-jackson:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-jdbc:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-micrometer-metrics:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-micrometer-observation:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-persistence:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-servlet:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-sql:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-actuator:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-jackson:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-jdbc:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-logging:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-micrometer-metrics:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-test:jar:4.0.9:test | test | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-tomcat-runtime:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-tomcat:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-validation:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter-web:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-starter:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-test-autoconfigure:jar:4.0.9:test | test | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-test:jar:4.0.9:test | test | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-tomcat:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-transaction:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-validation:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-web-server:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot-webmvc:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.boot:spring-boot:jar:4.0.9:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework.security:spring-security-crypto:jar:7.0.8:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-aop:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-beans:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-context:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-core:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-expression:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-jdbc:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-test:jar:7.0.10:test | test | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-tx:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-web:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.springframework:spring-webmvc:jar:7.0.10:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| org.xmlunit:xmlunit-core:jar:2.10.4:test | test | The Apache Software License, Version 2.0 | inherited_parent_pom |
| org.yaml:snakeyaml:jar:2.5:compile | compile | Apache License, Version 2.0 | declared_in_pom |
| tools.jackson.core:jackson-core:jar:3.1.7:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |
| tools.jackson.core:jackson-databind:jar:3.1.7:compile | compile | The Apache Software License, Version 2.0 | declared_in_pom |

## npm lock声明

| 包/版本 | 类型 | 声明许可证 |
|---|---|---|
| @ampproject/remapping@2.3.0 | dev | Apache-2.0 |
| @babel/code-frame@7.29.7 | 非dev | MIT |
| @babel/compat-data@7.29.7 | 非dev | MIT |
| @babel/core@7.29.7 | 非dev | MIT |
| @babel/generator@7.29.7 | 非dev | MIT |
| @babel/helper-compilation-targets@7.29.7 | 非dev | MIT |
| @babel/helper-globals@7.29.7 | 非dev | MIT |
| @babel/helper-module-imports@7.29.7 | 非dev | MIT |
| @babel/helper-module-transforms@7.29.7 | 非dev | MIT |
| @babel/helper-plugin-utils@7.29.7 | 非dev | MIT |
| @babel/helper-string-parser@7.29.7 | 非dev | MIT |
| @babel/helper-validator-identifier@7.29.7 | 非dev | MIT |
| @babel/helper-validator-option@7.29.7 | 非dev | MIT |
| @babel/helpers@7.29.7 | 非dev | MIT |
| @babel/parser@7.29.7 | 非dev | MIT |
| @babel/plugin-transform-react-jsx-self@7.29.7 | 非dev | MIT |
| @babel/plugin-transform-react-jsx-source@7.29.7 | 非dev | MIT |
| @babel/template@7.29.7 | 非dev | MIT |
| @babel/traverse@7.29.7 | 非dev | MIT |
| @babel/types@7.29.7 | 非dev | MIT |
| @bcoe/v8-coverage@1.0.2 | dev | MIT |
| @esbuild/aix-ppc64@0.28.1 | 非dev/optional | MIT |
| @esbuild/android-arm@0.28.1 | 非dev/optional | MIT |
| @esbuild/android-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/android-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/darwin-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/darwin-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/freebsd-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/freebsd-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-arm@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-ia32@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-loong64@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-mips64el@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-ppc64@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-riscv64@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-s390x@0.28.1 | 非dev/optional | MIT |
| @esbuild/linux-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/netbsd-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/netbsd-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/openbsd-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/openbsd-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/openharmony-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/sunos-x64@0.28.1 | 非dev/optional | MIT |
| @esbuild/win32-arm64@0.28.1 | 非dev/optional | MIT |
| @esbuild/win32-ia32@0.28.1 | 非dev/optional | MIT |
| @esbuild/win32-x64@0.28.1 | 非dev/optional | MIT |
| @isaacs/cliui@8.0.2 | dev | ISC |
| @istanbuljs/schema@0.1.6 | dev | MIT |
| @jridgewell/gen-mapping@0.3.13 | 非dev | MIT |
| @jridgewell/remapping@2.3.5 | 非dev | MIT |
| @jridgewell/resolve-uri@3.1.2 | 非dev | MIT |
| @jridgewell/sourcemap-codec@1.5.5 | 非dev | MIT |
| @jridgewell/trace-mapping@0.3.31 | 非dev | MIT |
| @pkgjs/parseargs@0.11.0 | dev/optional | MIT |
| @rolldown/pluginutils@1.0.0-beta.27 | 非dev | MIT |
| @rollup/rollup-android-arm-eabi@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-android-arm64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-darwin-arm64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-darwin-x64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-freebsd-arm64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-freebsd-x64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-arm-gnueabihf@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-arm-musleabihf@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-arm64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-arm64-musl@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-loong64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-loong64-musl@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-ppc64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-ppc64-musl@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-riscv64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-riscv64-musl@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-s390x-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-x64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-linux-x64-musl@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-openbsd-x64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-openharmony-arm64@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-win32-arm64-msvc@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-win32-ia32-msvc@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-win32-x64-gnu@4.62.2 | 非dev/optional | MIT |
| @rollup/rollup-win32-x64-msvc@4.62.2 | 非dev/optional | MIT |
| @types/babel__core@7.20.5 | 非dev | MIT |
| @types/babel__generator@7.27.0 | 非dev | MIT |
| @types/babel__template@7.4.4 | 非dev | MIT |
| @types/babel__traverse@7.28.0 | 非dev | MIT |
| @types/chai@5.2.3 | dev | MIT |
| @types/deep-eql@4.0.2 | dev | MIT |
| @types/estree@1.0.9 | 非dev | MIT |
| @types/trusted-types@2.0.7 | 非dev/optional | MIT |
| @vitejs/plugin-react@4.7.0 | 非dev | MIT |
| @vitest/coverage-v8@3.2.7 | dev | MIT |
| @vitest/expect@3.2.7 | dev | MIT |
| @vitest/mocker@3.2.7 | dev | MIT |
| @vitest/pretty-format@3.2.7 | dev | MIT |
| @vitest/runner@3.2.7 | dev | MIT |
| @vitest/snapshot@3.2.7 | dev | MIT |
| @vitest/spy@3.2.7 | dev | MIT |
| @vitest/utils@3.2.7 | dev | MIT |
| ansi-regex@6.3.0 | dev | MIT |
| ansi-styles@6.2.3 | dev | MIT |
| assertion-error@2.0.1 | dev | MIT |
| ast-v8-to-istanbul@0.3.12 | dev | MIT |
| js-tokens@10.0.0 | dev | MIT |
| balanced-match@4.0.4 | dev | MIT |
| baseline-browser-mapping@2.10.38 | 非dev | Apache-2.0 |
| brace-expansion@5.0.9 | dev | MIT |
| browserslist@4.28.4 | 非dev | MIT |
| cac@6.7.14 | dev | MIT |
| caniuse-lite@1.0.30001799 | 非dev | CC-BY-4.0 |
| chai@5.3.3 | dev | MIT |
| check-error@2.1.3 | dev | MIT |
| color-convert@2.0.1 | dev | MIT |
| color-name@1.1.4 | dev | MIT |
| convert-source-map@2.0.0 | 非dev | MIT |
| cross-spawn@7.0.6 | dev | MIT |
| debug@4.4.3 | 非dev | MIT |
| deep-eql@5.0.2 | dev | MIT |
| dompurify@3.4.11 | 非dev | (MPL-2.0 OR Apache-2.0) |
| eastasianwidth@0.2.0 | dev | MIT |
| electron-to-chromium@1.5.378 | 非dev | ISC |
| emoji-regex@9.2.2 | dev | MIT |
| es-module-lexer@1.7.0 | dev | MIT |
| esbuild@0.28.1 | 非dev | MIT |
| escalade@3.2.0 | 非dev | MIT |
| estree-walker@3.0.3 | dev | MIT |
| expect-type@1.4.0 | dev | Apache-2.0 |
| fdir@6.5.0 | 非dev | MIT |
| foreground-child@3.3.1 | dev | ISC |
| fsevents@2.3.3 | 非dev/optional | MIT |
| gensync@1.0.0-beta.2 | 非dev | MIT |
| glob@10.4.5 | dev | ISC |
| balanced-match@1.0.2 | dev | MIT |
| brace-expansion@2.1.4 | dev | MIT |
| minimatch@9.0.9 | dev | ISC |
| has-flag@4.0.0 | dev | MIT |
| html-escaper@2.0.2 | dev | MIT |
| is-fullwidth-code-point@3.0.0 | dev | MIT |
| isexe@2.0.0 | dev | ISC |
| istanbul-lib-coverage@3.2.2 | dev | BSD-3-Clause |
| istanbul-lib-report@3.0.1 | dev | BSD-3-Clause |
| istanbul-lib-source-maps@5.0.6 | dev | BSD-3-Clause |
| istanbul-reports@3.2.0 | dev | BSD-3-Clause |
| jackspeak@3.4.3 | dev | BlueOak-1.0.0 |
| js-tokens@4.0.0 | 非dev | MIT |
| jsesc@3.1.0 | 非dev | MIT |
| json5@2.2.3 | 非dev | MIT |
| loupe@3.2.1 | dev | MIT |
| lru-cache@5.1.1 | 非dev | ISC |
| lucide-react@0.468.0 | 非dev | ISC |
| magic-string@0.30.21 | dev | MIT |
| magicast@0.3.5 | dev | MIT |
| make-dir@4.0.0 | dev | MIT |
| semver@7.8.5 | dev | ISC |
| marked@15.0.12 | 非dev | MIT |
| minimatch@10.2.6 | dev | BlueOak-1.0.0 |
| minipass@7.1.3 | dev | BlueOak-1.0.0 |
| ms@2.1.3 | 非dev | MIT |
| nanoid@3.3.15 | 非dev | MIT |
| node-releases@2.0.50 | 非dev | MIT |
| package-json-from-dist@1.0.1 | dev | BlueOak-1.0.0 |
| path-key@3.1.1 | dev | MIT |
| path-scurry@1.11.1 | dev | BlueOak-1.0.0 |
| lru-cache@10.4.3 | dev | ISC |
| pathe@2.0.3 | dev | MIT |
| pathval@2.0.1 | dev | MIT |
| picocolors@1.1.1 | 非dev | ISC |
| picomatch@4.0.4 | 非dev | MIT |
| postcss@8.5.15 | 非dev | MIT |
| react@19.2.7 | 非dev | MIT |
| react-dom@19.2.7 | 非dev | MIT |
| react-refresh@0.17.0 | 非dev | MIT |
| rollup@4.62.2 | 非dev | MIT |
| scheduler@0.27.0 | 非dev | MIT |
| semver@6.3.1 | 非dev | ISC |
| shebang-command@2.0.0 | dev | MIT |
| shebang-regex@3.0.0 | dev | MIT |
| siginfo@2.0.0 | dev | ISC |
| signal-exit@4.1.0 | dev | ISC |
| source-map-js@1.2.1 | 非dev | BSD-3-Clause |
| stackback@0.0.2 | dev | MIT |
| std-env@3.10.0 | dev | MIT |
| string-width@5.1.2 | dev | MIT |
| string-width@4.2.3 | dev | MIT |
| ansi-regex@5.0.1 | dev | MIT |
| emoji-regex@8.0.0 | dev | MIT |
| strip-ansi@6.0.1 | dev | MIT |
| strip-ansi@7.2.0 | dev | MIT |
| strip-ansi@6.0.1 | dev | MIT |
| ansi-regex@5.0.1 | dev | MIT |
| strip-literal@3.1.0 | dev | MIT |
| js-tokens@9.0.1 | dev | MIT |
| supports-color@7.2.0 | dev | MIT |
| test-exclude@7.0.2 | dev | ISC |
| tinybench@2.9.0 | dev | MIT |
| tinyexec@0.3.2 | dev | MIT |
| tinyglobby@0.2.17 | 非dev | MIT |
| tinypool@1.1.1 | dev | MIT |
| tinyrainbow@2.0.0 | dev | MIT |
| tinyspy@4.0.4 | dev | MIT |
| update-browserslist-db@1.2.3 | 非dev | MIT |
| vite@7.3.6 | 非dev | MIT |
| vite-node@3.2.4 | dev | MIT |
| vitest@3.2.7 | dev | MIT |
| which@2.0.2 | dev | ISC |
| why-is-node-running@2.3.0 | dev | MIT |
| wrap-ansi@8.1.0 | dev | MIT |
| wrap-ansi@7.0.0 | dev | MIT |
| ansi-regex@5.0.1 | dev | MIT |
| ansi-styles@4.3.0 | dev | MIT |
| emoji-regex@8.0.0 | dev | MIT |
| string-width@4.2.3 | dev | MIT |
| strip-ansi@6.0.1 | dev | MIT |
| yallist@3.1.1 | 非dev | ISC |

本项目根LICENSE文本为Apache License 2.0；根许可证保留，不推断它替代第三方各自条款。双许可证保留原声明，未自动替使用者选择授权路径。JDK/JRE、nginx、Node、PG/pgvector镜像的OS层完整SBOM/NOTICE未在本次本地应用清单核实，单列待补。
