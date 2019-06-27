[![Build Status](https://travis-ci.com/GoncaloGarcia/DistTracing.svg?token=UL4UejHxEkoG4ZY6Wp8v&branch=master)](https://travis-ci.com/GoncaloGarcia/DistTracing) [![Codacy Badge](https://api.codacy.com/project/badge/Grade/64c32ebc68a7406c92ec68292cc5e1ac)](https://www.codacy.com?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=GoncaloGarcia/DistTracing&amp;utm_campaign=Badge_Grade)[![codecov](https://codecov.io/gh/GoncaloGarcia/DistTracing/branch/master/graph/badge.svg?token=zeWoZDlzAU)](https://codecov.io/gh/GoncaloGarcia/DistTracing)



# Description

This project is a simplified API for tracing Java based distributed systems, as well as an implementation based on the OpenTracing instrumentation framework and the Jaeger tracing engine.

In this book you will first find a small explanation of the tracing model, then a high-level description of the APIs and some instruction on when to use each API.

# Installation

This project is avaliable on Maven Central. To use it in your projects add the following to your `pom.xml`

```
<dependency>
    <groupId>com.feedzai.commons.tracing</groupId>
    <artifactId>tracing</artifactId>
    <version>0.1.10</version>
    <type>pom</type>
</dependency>
```

# Usage

For an API description along with usage examples please refer to the [Documentation](site/src/gitbook/DESCRIPTION.md)

# Build

Run the following command:

```
mvn clean install
```

To build without running tests execute:

```
mvn clean install -DskipTests
```




