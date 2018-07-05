package com.titisan.ldap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldap.sdk.OperationType;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;

/**
 * Unit test for simple LdapCollectorTest.
 */
public class LdapCollectorTest {
    CollectorRegistry registry;
    private static InMemoryDirectoryServer server = null;
    private static final Logger logger = Logger.getLogger(LdapCollectorTest.class.getName());;
    
    /**
     * For debugging.
     */
    private static void log(String msg) {
        logger.log(Level.FINE, msg);
    }
  
    private static void startLDAPServer() throws Exception {
        InMemoryDirectoryServerConfig serverConfig = new InMemoryDirectoryServerConfig("cn=Monitor");
        serverConfig.addAdditionalBindCredentials("cn=Directory Manager", "password");
        serverConfig.setAuthenticationRequiredOperationTypes(OperationType.SEARCH);
        // Do not set any schema
        serverConfig.setSchema(null);
        serverConfig.setListenerConfigs(
            InMemoryListenerConfig.createLDAPConfig("nonEncrypted389", 389));
        server = new InMemoryDirectoryServer(serverConfig);

        // Populate data in the cn=Monitor.
        log("Populating CN Monitor data from LDIF file.");
	    File ldifFile = new File(LdapCollectorTest.class.getClassLoader().getResource("cnMonitorTestData.ldif").getFile());

        server.importFromLDIF(true, new LDIFReader(ldifFile));
        server.startListening();
    }

    @BeforeClass
    public static void OneTimeSetUp() throws Exception {
        // Start the Ldap Server.
        startLDAPServer();
        
        
    }
    @AfterClass
    public static void onTimeTearDown() throws Exception {
        if (server != null)
            server.shutDown(true); 
    }

    @Before
    public void setUp() throws Exception {
      registry = new CollectorRegistry();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRulesMustHaveNameWithHelp() throws Exception {
        LdapCollector lc = new LdapCollector("---\nrules:\n- help: foo");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRulesMustHaveNameWithLabels() throws Exception {
        LdapCollector lc = new LdapCollector("---\nrules:\n- labels: {}");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRulesMustHavePatternWithName() throws Exception {
        LdapCollector lc = new LdapCollector("---\nrules:\n- name: foo");
    }
    
    @Test
    public void testNameIsReplacedOnMatch() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{}, new String[]{}), .001);
    }

    @Test
    public void testWrongLdapUrl() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nldapUrl: ldap://127.0.0.1:399\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total".replace('`','"')).register(registry);
      assertEquals(1.0, registry.getSampleValue("ldap_scrape_error"), .001);
    }

    @Test
    public void testLdapUrl() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nldapUrl: ldap://127.0.0.1:389\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{}, new String[]{}), .001);
    }

    @Test
    public void testLdapWrongUserNamePassword() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nldapUrl: ldap://127.0.0.1:389\nusername: cn=Directory Manager\npassword: wrongpassword\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total".replace('`','"')).register(registry);
        assertEquals(1.0, registry.getSampleValue("ldap_scrape_error"), .001);
    }   

    @Test
    public void testLdapUserNamePassword() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nldapUrl: ldap://127.0.0.1:389\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{}, new String[]{}), .001);
    }    

    @Test
    public void testLabelsAreSet() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  labels:\n    l: v".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{"l"}, new String[]{"v"}), .001);
    }

    @Test
    public void testEmptyLabelsAreIgnored() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  labels:\n    '': v\n    l: ''".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{}, new String[]{}), .001);
    }    

    @Test
    public void testLowercaseOutputName() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nlowercaseOutputName: true\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: Connections_Total".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("connections_total", new String[]{}, new String[]{}), .001);
    }
    
    @Test
    public void testLowercaseOutputLabelNames() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nlowercaseOutputLabelNames: true\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: Connections_Total\n  labels:\n    ABC: DEF".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("Connections_Total", new String[]{"abc"}, new String[]{"DEF"}), .001);
    }

    @Test
    public void testNameAndLabelsFromPattern() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=(Total),cn=(Connections)`\n  name: ldap_$2_$1\n  labels:\n    $1: $2".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("ldap_Connections_Total", new String[]{"Total"}, new String[]{"Connections"}), .001);
    }
    
    @Test
    public void testNameAndLabelSanatized() throws Exception {
      LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `(cn=Total,cn=Connections)`\n  name: $1\n  labels:\n    $1: $1".replace('`','"')).register(registry);
      assertEquals(15931071, registry.getSampleValue("_Total_Connections", new String[]{"_Total_Connections"}, new String[]{"cn=Total,cn=Connections"}), .001);
    }

    @Test
    public void testHelpFromPattern() throws Exception {
        LdapCollector lc = new LdapCollector(
            "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=(Connections)`\n  name: connections_total\n  help: bar $1".replace('`','"')).register(registry);
        for(Collector.MetricFamilySamples mfs : lc.collect()) {
            if (mfs.name.equals("connections_total") && mfs.help.equals("bar Connections")) {
                return;
            }
        }
        fail("MetricFamilySamples connections_total with help 'bar Connections' not found.");
    }

    @Test
    public void stopsOnFirstMatchingRule() throws Exception {
        LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `.+`\n  name: foo\n- pattern: `.+`\n  name: bar".replace('`','"')).register(registry);
      assertNotNull(registry.getSampleValue("foo"));
      assertNull(registry.getSampleValue("bar"));
    }

    @Test
    public void stopsOnEmptyName() throws Exception {
        LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `.*`\n  name: ''\n- pattern: `.*`\n  name: foo".replace('`','"')).register(registry);
      assertNull(registry.getSampleValue("foo"));
    }

    @Test
    public void ignoreWrongRule() throws Exception {
        LdapCollector lc = new LdapCollector(
              "\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=(*)`\n  name: foo\n- pattern: `.+`\n  name: bar".replace('`','"')).register(registry);
      assertNull(registry.getSampleValue("foo"));
      assertNotNull(registry.getSampleValue("bar"));
    }    

    @Test
    public void defaultExportTest() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\n").register(registry);

        // Test 'cn=Current,cn=Connections'
        assertNotNull(registry.getSampleValue("_Current_Connections"));

        // Test 'cn=Total,cn=Connections'
        assertEquals(15931071, registry.getSampleValue("_Total_Connections"), .001);
        // Test 'cn=Bind,cn=Operations_monitorOpInitiated'
        assertEquals(3960532, registry.getSampleValue("_Bind_Operations_monitorOpInitiated"), .001);

        // Test 'cn=Entries,cn=Statistics'
        assertEquals(19858576, registry.getSampleValue("_Entries_Statistics"), .001);

        // Test 'cn=Backend 1,cn=Backends,cn=Monitor'
        assertNull(registry.getSampleValue("_Backend_1_Backends"));
        // Test 'cn=Max,cn=Threads,cn=Monitor'
        assertEquals(16.0, registry.getSampleValue("_Max_Threads"), .001);
    }

    @Test
    public void testWhitelist() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nwhitelistEntryNames:\n- entryDN=cn=Current,cn=Connections,cn=Monitor\n- entryDN=cn=Total,cn=Connections,cn=Monitor\n- entryDN=cn=Bytes,cn=Statistics,cn=Monitor".replace('`','"')).register(registry);

        // Test what should and shouldn't be present.
        assertNotNull(registry.getSampleValue("_Current_Connections"));
        assertNotNull(registry.getSampleValue("_Total_Connections"));
        assertNotNull(registry.getSampleValue("_Bytes_Statistics"));

        assertNull(registry.getSampleValue("_Bind_Operations_monitorOpInitiated"));
        assertNull(registry.getSampleValue("_Entries_Statistics"));
        assertNull(registry.getSampleValue("_Max_File_Descriptors_Connections"));
        
    }

    @Test
    public void testBlacklist() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nblacklistEntryNames:\n- entryDN=cn=Current,cn=Connections,cn=Monitor\n- entryDN=cn=Total,cn=Connections,cn=Monitor\n- entryDN=cn=Bytes,cn=Statistics,cn=Monitor".replace('`','"')).register(registry);

        // Test what should and shouldn't be present.
        assertNull(registry.getSampleValue("_Current_Connections"));
        assertNull(registry.getSampleValue("_Total_Connections"));
        assertNull(registry.getSampleValue("_Bytes_Statistics"));

        assertNotNull(registry.getSampleValue("_Bind_Operations_monitorOpInitiated"));
        assertNotNull(registry.getSampleValue("_Entries_Statistics"));
        assertNotNull(registry.getSampleValue("_Max_File_Descriptors_Connections"));
        
    }

    @Test
    public void testDefaultExportLowercaseOutputName() throws Exception {
        LdapCollector lc = new LdapCollector("---\nusername: cn=Directory Manager\npassword: password\nlowercaseOutputName: true").register(registry);

        // Test 'cn=Current,cn=Connections'
        assertNotNull(registry.getSampleValue("_current_connections"));
    }

    @Test
    public void testValueEmpty() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  value:".replace('`','"')).register(registry);
        assertNull(registry.getSampleValue("connections_total"));
    }

    @Test
    public void testValueStatic() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  value: 1".replace('`','"')).register(registry);
        assertEquals(1.0, registry.getSampleValue("connections_total"), .001);
    }

    @Test
    public void testValueIgnoreNonNumber() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  value: a".replace('`','"')).register(registry);
        assertNull(registry.getSampleValue("connections_total"));
    }    

    @Test
    public void testValueFactorEmpty() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  value: 1\n  valueFactor:".replace('`','"')).register(registry);
        assertEquals(1.0, registry.getSampleValue("connections_total"), .001);
    }

    @Test
    public void testValueFactor() throws Exception {
        LdapCollector lc = new LdapCollector("\n---\nusername: cn=Directory Manager\npassword: password\nrules:\n- pattern: `cn=Total,cn=Connections`\n  name: connections_total\n  value: 1\n  valueFactor: 0.001".replace('`','"')).register(registry);
        assertEquals(0.001, registry.getSampleValue("connections_total"), .001);
    }

    @Test(expected=IllegalStateException.class)
    public void testDelayedStartNotReady() throws Exception {
        LdapCollector lc = new LdapCollector("---\nusername: cn=Directory Manager\npassword: password\nstartDelaySeconds: 1").register(registry);
        assertNull(registry.getSampleValue("_Current_Connections"));
        fail();
    }

    @Test
    public void testDelayedStartReady() throws Exception {
        LdapCollector lc = new LdapCollector("---\nusername: cn=Directory Manager\npassword: password\nstartDelaySeconds: 1").register(registry);
        Thread.sleep(2000);
        assertEquals(45.0, registry.getSampleValue("_Current_Connections"), .001);
    }    
}
