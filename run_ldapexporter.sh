#!/usr/bin/env bash
# Script to run the LDAP exporter

# Usage: run_ldapexporter.sh <[hostname:]port> <[yaml configuration file]>
# If no configuration file is provided it connects to ldap://127.0.0.1:389
java $JAVA_OPTS -jar ldap_exporter_httpserver/target/ldap_exporter_httpserver-0.1.0-SNAPSHOT-jar-with-dependencies.jar $1 $2