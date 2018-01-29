LDAP Exporter
=====

LDAP to Prometheus exporter: a collector that can configurably scrape and expose metrics from a LDAP directory server.

This exporter is intended to be run as an independent HTTP server and scrape remote LDAP targets.

## Running

Execute `./run_ldapexporter.sh <[hostname:]port> [<yaml configuration file>]` 

## Building

`mvn package` to build.

## Configuration
The configuration is in YAML. An example with all possible options:
```yaml
---
startDelaySeconds: 0
ldapUrl: ldap://127.0.0.1:1234
userName: cn=Directory Manager
password: password
lowercaseOutputName: false
lowercaseOutputLabelNames: false
whitelistObjectNames: ["entryDN=cn=Current,cn=Connections,cn=Monitor"]
blacklistObjectNames: ["entryDN=cn=Total,cn=Connections,cn=Monitor"]
rules:
  - pattern: 'cn=Bytes,cn=Statistics,cn=Monitor'
    name: num_bytes
    value: 
    valueFactor: 0.001
    labels: {}
    help: "LDAP metric number of bytes sent"
    type: COUNTER
    attrNameSnakeCase: false
```
Name     | Description
---------|------------
startDelaySeconds | start delay before serving requests. Any requests within the delay period will result in an empty metrics set.
ldapUrl  | The full LDAP URL of the LDAP directory server to connect to. Defaults to 'ldap://127.0.0.1:389'
username | The username to be used in LDAP authentication.
password | The password to be used in LDAP authentication.
lowercaseOutputName | Lowercase the output metric name. Applies to default format and `name`. Defaults to false.
lowercaseOutputLabelNames | Lowercase the output metric label names. Applies to default format and `labels`. Defaults to false.
whitelistEntryNames | A list of [LDAP attributes](https://tools.ietf.org/html/rfc4512#section-2.5) to query. The list is used in the LDAP search filter. Defaults to all "(objectClass=*)".
blacklistEntryNames | A list of [LDAP attributes](https://tools.ietf.org/html/rfc4512#section-2.5) to not query. Takes precedence over `whitelistObjectNames`. Defaults to none.
rules    | A list of rules to apply in order, processing stops at the first matching rule. Attributes that aren't matched aren't collected. If not specified, defaults to collecting everything in the default format.
pattern  | Regex pattern to match against each LDAP entry. The pattern is not anchored. Capture groups can be used in other options. Defaults to matching everything.
name     | The metric name to set. Capture groups from the `pattern` can be used. If not specified, the default format will be used. If it evaluates to empty, processing of this attribute stops with no output.
value    | Value for the metric. Static values and capture groups from the `pattern` can be used. If not specified the scraped LDAP entry value will be used.
valueFactor | Optional number that `value` (or the scraped LDAP entry value if `value` is not specified) is multiplied by, mainly used to convert values from milliseconds to seconds.
labels   | A map of label name to label value pairs. Capture groups from `pattern` can be used in each. `name` must be set to use this. Empty names and values are ignored. If not specified and the default format is not being used, no labels are set.
help     | Help text for the metric. Capture groups from `pattern` can be used. `name` must be set to use this. Defaults to "Metric from <LDAP entryDN>_<LDAP attribute>".
type     | The type of the metric, can be `GAUGE`, `COUNTER` or `UNTYPED`. `name` must be set to use this. Defaults to `UNTYPED`.

Metric names and label names are sanitized. All characters other than `[a-zA-Z0-9:_]` are replaced with underscores,
and adjacent underscores are collapsed, the pattern `,*cn=` is replaced by underscores. There's no limitations on label values or the help text.

A minimal config is `---`, which will connect using anonymous authentication to ldap://127.0.0.1:389 and collect everything in the default format.

### Pattern input
The format of the input matches against the pattern is "LDAP entry Dn[_attrname]"

When the LDAP entry contains more than one of these attributes `monitorCounter`, `monitorOpInitiated`, `monitorOpCompleted` and `monitoredInfo` the attribute name is appended to the LDAP entry name. 

Examples:
```
cn=Current,cn=Connections
cn=Bind,cn=Operations_monitorOpInitiated
```

### Default format
The default format will transform LDAP DNs in a way that should produce sane metrics in most cases.

Examples:
```
_Current_Connections
_Bind_Operations_monitorOpInitiated
```

## Testing

`mvn test` to test.

## Debugging

You can start the LDAP's scraper in standlone mode in order to debug what is called 

`java -cp ldapcollector.jar io.prometheus.ldap.LdapScraper  ldap://your_url:your_port`

To get finer logs (including the duration of each LDAP request),
create a file called logging.properties with this content:

```
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
io.prometheus.ldap.level=ALL
```

Add the following flag to your Java invocation:

`-Djava.util.logging.config.file=/path/to/logging.properties`

