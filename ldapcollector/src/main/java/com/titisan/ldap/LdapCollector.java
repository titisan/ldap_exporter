package com.titisan.ldap;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.management.MalformedObjectNameException;

import org.yaml.snakeyaml.Yaml;
import static java.lang.String.format;

import io.prometheus.client.Collector;
import io.prometheus.client.Counter;

public class LdapCollector extends Collector implements Collector.Describable {
    static final Counter configReloadSuccess = Counter.build()
      .name("ldapexporter_config_reload_success_total")
      .help("Number of times configuration have successfully been reloaded.").register();

    static final Counter configReloadFailure = Counter.build()
      .name("ldapexporter_config_reload_failure_total")
      .help("Number of times configuration have failed to be reloaded.").register();

    private static final Logger LOGGER = Logger.getLogger(LdapCollector.class.getName());

    private static class Rule {
      Pattern pattern;
      String name;
      String value;
      Double valueFactor = 1.0;
      String help;
      Type type = Type.UNTYPED;
      ArrayList<String> labelNames;
      ArrayList<String> labelValues;
    }

    private static class Config {
      Integer startDelaySeconds = 0;
      String ldapUrl = "ldap://127.0.0.1:389"; //default ldap URL if not provided
      String username = "";
      String password = "";
      boolean lowercaseOutputName;
      boolean lowercaseOutputLabelNames;
      List<String> whitelistEntryNames = new ArrayList<String>();
      List<String> blacklistEntryNames = new ArrayList<String>();
      ArrayList<Rule> rules = new ArrayList<Rule>();
      long lastUpdate = 0L;
    }

    private Config config;
    private File configFile;
    private long createTimeNanoSecs = System.nanoTime();

    public LdapCollector(File in) throws IOException, MalformedObjectNameException {
        configFile = in;
        FileReader F_reader = null;
        try {
          F_reader = new FileReader(in); 
          config = loadConfig((Map<String, Object>) new Yaml().load(F_reader));
          config.lastUpdate = configFile.lastModified();
        } catch (IOException  e) {
          LOGGER.severe("Configuration load failed: " + e.toString());
        } catch (MalformedObjectNameException e) {
          LOGGER.severe("Configuration load failed: " + e.toString());
        }
        finally {
          if (F_reader != null)  {
            F_reader.close();
          }
        }
    }

    public LdapCollector(String yamlConfig) throws MalformedObjectNameException {
        config = loadConfig((Map<String, Object>)new Yaml().load(yamlConfig));
    }

    private void reloadConfig() {
      try {
        FileReader fr = new FileReader(configFile);

        try {
          Map<String, Object> newYamlConfig = (Map<String, Object>)new Yaml().load(fr);
          config = loadConfig(newYamlConfig);
          config.lastUpdate = configFile.lastModified();
          configReloadSuccess.inc();
        } catch (Exception e) {
          LOGGER.severe("Configuration reload failed: " + e.toString());
          configReloadFailure.inc();
        } finally {
          fr.close();
        }

      } catch (IOException e) {
        LOGGER.severe("Configuration reload failed: " + e.toString());
        configReloadFailure.inc();
      }
    }

    private Config loadConfig(Map<String, Object> yamlConfig) throws MalformedObjectNameException {
        Config cfg = new Config();

        if (yamlConfig == null) {  // Yaml config empty, set config to empty map.
          yamlConfig = new HashMap<String, Object>();
        }

        if (yamlConfig.containsKey("startDelaySeconds")) {
          try {
            cfg.startDelaySeconds = (Integer) yamlConfig.get("startDelaySeconds");
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number provided for startDelaySeconds", e);
          }
        }
        //ldapUrl is optional, if not present default is "ldap://127.0.0.1:389"
        if (yamlConfig.containsKey("ldapUrl")) {
          cfg.ldapUrl = (String)yamlConfig.get("ldapUrl");
        }

        if (yamlConfig.containsKey("username")) {
          cfg.username = (String)yamlConfig.get("username");
        }
        
        if (yamlConfig.containsKey("password")) {
          cfg.password = (String)yamlConfig.get("password");
        }
      
        if (yamlConfig.containsKey("lowercaseOutputName")) {
          cfg.lowercaseOutputName = (Boolean)yamlConfig.get("lowercaseOutputName");
        }

        if (yamlConfig.containsKey("lowercaseOutputLabelNames")) {
          cfg.lowercaseOutputLabelNames = (Boolean)yamlConfig.get("lowercaseOutputLabelNames");
        }

        if (yamlConfig.containsKey("whitelistEntryNames")) {
          List<String> names = (List<String>) yamlConfig.get("whitelistEntryNames");
          for(String name : names) {
            cfg.whitelistEntryNames.add(name);
          }
        } 

        if (yamlConfig.containsKey("blacklistEntryNames")) {
          List<String> names = (List<String>) yamlConfig.get("blacklistEntryNames");
          for (String name : names) {
            cfg.blacklistEntryNames.add(name);
          }
        }

        if (yamlConfig.containsKey("rules")) {
          List<Map<String,Object>> configRules = (List<Map<String,Object>>) yamlConfig.get("rules");
          for (Map<String, Object> ruleObject : configRules) {
            Map<String, Object> yamlRule = ruleObject;
            Rule rule = new Rule();

            if (yamlRule.containsKey("pattern")) {
              try {
                rule.pattern = Pattern.compile((String)yamlRule.get("pattern"));
              } catch (PatternSyntaxException e) {
                // ignore this rule if wrong pattern
                LOGGER.warning("Ignoring wrong patern: " + (String)yamlRule.get("pattern")  + " Error: " + e.toString());
                continue;
              }
              
            }
            if (yamlRule.containsKey("name")) {
              rule.name = (String)yamlRule.get("name");
            }
            if (yamlRule.containsKey("value")) {
              rule.value = String.valueOf(yamlRule.get("value"));
            }
            if (yamlRule.containsKey("valueFactor")) {
              String valueFactor = String.valueOf(yamlRule.get("valueFactor"));
              try {
                rule.valueFactor = Double.valueOf(valueFactor);
              } catch (NumberFormatException e) {
                // use default value
              }
            }
            if (yamlRule.containsKey("type")) {
              rule.type = Type.valueOf((String)yamlRule.get("type"));
            }
            if (yamlRule.containsKey("help")) {
              rule.help = (String)yamlRule.get("help");
            }
            if (yamlRule.containsKey("labels")) {
              TreeMap labels = new TreeMap((Map<String, Object>)yamlRule.get("labels"));
              rule.labelNames = new ArrayList<String>();
              rule.labelValues = new ArrayList<String>();
              for (Map.Entry<String, Object> entry : (Set<Map.Entry<String, Object>>)labels.entrySet()) {
                rule.labelNames.add(entry.getKey());
                rule.labelValues.add((String)entry.getValue());
              }
            }

            // Validation.
            if ((rule.labelNames != null || rule.help != null) && rule.name == null) {
              throw new IllegalArgumentException("Must provide name, if help or labels are given: " + yamlRule);
            }
            if (rule.name != null && rule.pattern == null) {
              throw new IllegalArgumentException("Must provide pattern, if name is given: " + yamlRule);
            }
            // Add the rule to the configured rule (only after the validations are done)
            cfg.rules.add(rule);
          }
        } else {
          // Default to a single default rule.
          cfg.rules.add(new Rule());
        }

        return cfg;

    }

    class Receiver implements LdapScraper.LdapReceiver {
      Map<String, MetricFamilySamples> metricFamilySamplesMap =
        new HashMap<String, MetricFamilySamples>();

      private final Pattern unsafeChars = Pattern.compile("[^a-zA-Z0-9:_]");
      private final Pattern multipleUnderscores = Pattern.compile("__+");
      private final Pattern cnequals = Pattern.compile(",*cn=");

      private String safeName(String s) {
        // Change invalid chars to underscore, and merge underscores.
        return multipleUnderscores.matcher(unsafeChars.matcher(cnequals.matcher(s).replaceAll("_")).replaceAll("_")).replaceAll("_");
      }

      void addSample(MetricFamilySamples.Sample sample, Type type, String help) {
        MetricFamilySamples mfs = metricFamilySamplesMap.get(sample.name);
        if (mfs == null) {
          // LdapScraper.LdapReceiver is only called from one thread,
          // so there's no race here.
          mfs = new MetricFamilySamples(sample.name, type, help, new ArrayList<MetricFamilySamples.Sample>());
          metricFamilySamplesMap.put(sample.name, mfs);
        }
        mfs.samples.add(sample);
      }

      private void defaultExport(
          String entryName,
          String attrName,
          String help,
          Number value,
          Type type) {
          
        String fullname = safeName(entryName);

        if (config.lowercaseOutputName) {
          fullname = fullname.toLowerCase();
        }
         // Add to samples.
         LOGGER.fine("add metric sample, Name: " + fullname + 
         " Value: " + value.doubleValue() + 
         " help: " + help);
         addSample(new MetricFamilySamples.Sample(fullname, new ArrayList<String>(), new ArrayList<String>(), value.doubleValue()), type, help);
      }

      public void recordLdapEntry(
        String entryName,
        Number counterValue,
        String attrName,
        String attrDescription) {

        String help = "Metric from " + attrDescription;

        for (Rule rule : config.rules) {
          Matcher matcher = null;
          if (rule.pattern != null) {
            matcher = rule.pattern.matcher(entryName);
            if (!matcher.matches()) {
              continue;
            } 
          }

          Number value = Double.valueOf(0.0);
          if (rule.value != null && !rule.value.isEmpty()) {
            String val = matcher.replaceAll(rule.value);

            try {
                counterValue = Double.valueOf(val);
            } catch (NumberFormatException e) {
              LOGGER.fine("Unable to parse configured value '" + val + "' to number for entry: " + entryName + "_" + attrName + ": " + counterValue);
              return;
            }
          }
          value = ((Number)counterValue).doubleValue() * rule.valueFactor;

          // If there's no name provided, use default export format.
          if (rule.name == null) {
              //LOGGER.fine("No rule name provided, using defaultExport: " + entryName);
              defaultExport(entryName, attrName, help, value, Type.UNTYPED);
              return;
          }

          // Matcher is set below here due to validation in the constructor.
          String name = safeName(matcher.replaceAll(rule.name));
          if (name.isEmpty()) {
            return;
          }
          if (config.lowercaseOutputName) {
            name = name.toLowerCase();
          }

          // Set the help.
          if (rule.help != null) {
            help = matcher.replaceAll(rule.help);
          }

          // Set the labels.
          ArrayList<String> labelNames = new ArrayList<String>();
          ArrayList<String> labelValues = new ArrayList<String>();
          if (rule.labelNames != null) {
            for (int i = 0; i < rule.labelNames.size(); i++) {
              final String unsafeLabelName = rule.labelNames.get(i);
              final String labelValReplacement = rule.labelValues.get(i);
              try {
                String labelName = safeName(matcher.replaceAll(unsafeLabelName));
                String labelValue = matcher.replaceAll(labelValReplacement);
                if (config.lowercaseOutputLabelNames) {
                  labelName = labelName.toLowerCase();
                }
                if (!labelName.isEmpty() && !labelValue.isEmpty()) {
                  labelNames.add(labelName);
                  labelValues.add(labelValue);
                }
              } catch (Exception e) {
                throw new RuntimeException(
                  format("Matcher '%s' unable to use: '%s' value: '%s'", matcher, unsafeLabelName, labelValReplacement), e);
              }
            }
          }

          // Add to samples.
          LOGGER.fine("add metric sample, Name: " + name + 
                      " Value: " + value.doubleValue() + 
                      " Labels: " + labelNames.toString() +
                      " Label values: " + labelValues.toString() +
                      " help: " + help);
          addSample(new MetricFamilySamples.Sample(name, labelNames, labelValues, value.doubleValue()), rule.type, help);
          return;
        }
      }

    }

    public List<MetricFamilySamples> collect() {
      if (configFile != null) {
        long mtime = configFile.lastModified();
        if (mtime > config.lastUpdate) {
          LOGGER.fine("Configuration file changed, reloading...");
          reloadConfig();
        }
      }

      Receiver receiver = new Receiver();
      LdapScraper scraper = new LdapScraper(config.ldapUrl, config.username, config.password, config.whitelistEntryNames, config.blacklistEntryNames, receiver);
      long start = System.nanoTime();
      double error = 0;
      if ((config.startDelaySeconds > 0) &&
        ((start - createTimeNanoSecs) / 1000000000L < config.startDelaySeconds)) {
        throw new IllegalStateException("LdapCollector waiting for startDelaySeconds");
      }
      try {
        scraper.doScrape();
      } catch (Exception e) {
        error = 1;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        LOGGER.severe("LDAP scrape failed: " + sw.toString());
      }
      List<MetricFamilySamples> mfsList = new ArrayList<MetricFamilySamples>();
      mfsList.addAll(receiver.metricFamilySamplesMap.values());
      List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
      samples.add(new MetricFamilySamples.Sample(
          "ldap_scrape_duration_seconds", new ArrayList<String>(), new ArrayList<String>(), (System.nanoTime() - start) / 1.0E9));
      mfsList.add(new MetricFamilySamples("ldap_scrape_duration_seconds", Type.GAUGE, "Time this LDAP scrape took, in seconds.", samples));

      samples = new ArrayList<MetricFamilySamples.Sample>();
      samples.add(new MetricFamilySamples.Sample(
          "ldap_scrape_error", new ArrayList<String>(), new ArrayList<String>(), error));
      mfsList.add(new MetricFamilySamples("ldap_scrape_error", Type.GAUGE, "Non-zero if this scrape failed.", samples));
      return mfsList;
    }

    public List<MetricFamilySamples> describe() {
      List<MetricFamilySamples> sampleFamilies = new ArrayList<MetricFamilySamples>();
      sampleFamilies.add(new MetricFamilySamples("ldap_scrape_duration_seconds", Type.GAUGE, "Time this LDAP scrape took, in seconds.", new ArrayList<MetricFamilySamples.Sample>()));
      sampleFamilies.add(new MetricFamilySamples("ldap_scrape_error", Type.GAUGE, "Non-zero if this scrape failed.", new ArrayList<MetricFamilySamples.Sample>()));
      return sampleFamilies;
    }

    /**
     * Convenience function to run standalone.
     */
    public static void main(String[] args) throws Exception {
      String ldapUrl = "ldap://127.0.0.1:389";
      if (args.length > 0) {
        ldapUrl = args[0];
      }
      LdapCollector lc = new LdapCollector("\n---\nldapUrl: " + ldapUrl);
      
      for(MetricFamilySamples mfs : lc.collect()) {
        System.out.println(mfs);
      }
    }
}