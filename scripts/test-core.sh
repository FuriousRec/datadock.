#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p target/core-tests
java --module jdk.compiler/com.sun.tools.javac.Main -d target/core-tests \
  src/main/java/io/github/furiousrec/datadock/domain/*.java \
  src/test/java/io/github/furiousrec/datadock/domain/CoreChecks.java
java -cp target/core-tests io.github.furiousrec.datadock.domain.CoreChecks
