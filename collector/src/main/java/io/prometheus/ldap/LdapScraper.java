package io.prometheus.ldap;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;


public class LdapScraper {
    private static final String[] attributesToReturn= {"monitorCounter", "monitorOpInitiated", "monitorOpCompleted"};
    private static final String baseDn = "cn=Monitor";
    private static final Logger logger = Logger.getLogger(LdapScraper.class.getName());;
    private static final Pattern PROPERTY_PATTERN = Pattern.compile(
            "([^,=:\\*\\?]+)" + // Name - non-empty, anything but comma, equals, colon, star, or question mark
            "=" +  // Equals
            "(" + // Either
                "\"" + // Quoted
                    "(?:" + // A possibly empty sequence of
                    "[^\\\\\"]" + // Anything but backslash or quote
                    "|\\\\\\\\" + // or an escaped backslash
                    "|\\\\n" + // or an escaped newline
                    "|\\\\\"" + // or an escaped quote
                    "|\\\\\\?" + // or an escaped question mark
                    "|\\\\\\*" + // or an escaped star
                    ")*" +
                "\"" +
            "|" + // Or
                "[^,=:\"]*" + // Unquoted - can be empty, anything but comma, equals, colon, or quote
            ")");

    public static interface LdapReceiver {
        void recordLdapEntry(
            String entryName,
            Number counterValue,
            String attrName,
            String attrDescription);
    }

    private LdapReceiver receiver;
    private String ldapUrl;
    private String username;
    private String password;
    private boolean ssl;
    private List<String> whitelistEntryNames, blacklistEntryNames;

    public LdapScraper(String ldapUrl, String username, String password, boolean ssl, List<String> whitelistEntryNames, List<String> blacklistEntryNames, LdapReceiver receiver) {
        this.ldapUrl = ldapUrl;
        this.receiver = receiver;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
        this.whitelistEntryNames = whitelistEntryNames;
        this.blacklistEntryNames = blacklistEntryNames;
    }

    /**
      * Get a list of attributes on ldapUrl and scrape their values.
      *
      * Values are passed to the receiver in a single thread.
      */
    public void doScrape() throws Exception {
        DirContext dirConn = null;
        try {
            Hashtable<String,Object> environment = new Hashtable<String,Object>();
            environment.put(Context.PROVIDER_URL, ldapUrl);
            environment.put(Context.REFERRAL, "ignore");
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            environment.put("java.naming.ldap.version", "3");
            if (username != null && username.length() != 0 && password != null && password.length() != 0) {
                environment.put(Context.SECURITY_AUTHENTICATION, "simple");
                environment.put(Context.SECURITY_PRINCIPAL, username);
                environment.put(Context.SECURITY_CREDENTIALS, password);
            }
/*          if (ssl) {
                environment.put("java.naming.security.protocol", "ssl");
            }
*/
            dirConn = new InitialDirContext(environment);
            SearchControls ctls = new SearchControls();
            ctls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            ctls.setReturningAttributes(attributesToReturn);
            String filterStr = null;
            // Add white listed attr. names to the list of attributes to return
            // TO-DO test performance of this query compared with an empty list
            // of attributes to return.
            
            StringBuilder filter = new StringBuilder();
            if (whitelistEntryNames.size() > 0 || blacklistEntryNames.size() > 0) {
                filter.append("(&");
                if ( whitelistEntryNames.size() > 0) {
                    // Compose the filter by using the white listed entry dns
                    filter.append("(|");
                    for (String wlstr : whitelistEntryNames) {
                        filter.append("(" + wlstr + ") ");
                    }
                    filter.append(")");
                }
                if (blacklistEntryNames.size() > 0) {
                    // Compose the filter by using the black listed entry dns
                    filter.append("(&");
                    for (String blstr : blacklistEntryNames) {
                        filter.append("(!(" + blstr + "))");
                    }
                    filter.append(")");
                }
                filter.append(")");
                filterStr = filter.toString();
            }
             else {
                filterStr = new String("(objectClass=*)");
            }
            //logger.log(Level.FINE, "LDAP search filter: " + filterStr);

            long start = System.nanoTime();
            NamingEnumeration<SearchResult> searchResult = dirConn.search(baseDn, filterStr, ctls);
            logger.fine("TIME: " + (System.nanoTime() - start) + " ns for reading cn=Monitor data");
            scrapeBackMonitorData(searchResult);
        } finally {
          if (dirConn != null) {
            dirConn.close();
          }
        }
    }


    /*
    Process the LDAP search result, format the info to be sent to the receiver.
    */
    private void scrapeBackMonitorData(NamingEnumeration<SearchResult> ldapAnswer) {
        try {
            int num_entries = 0;
            while (ldapAnswer.hasMoreElements()) {
                SearchResult sr = (SearchResult)ldapAnswer.nextElement();
                Attributes attrs = sr.getAttributes();
                NamingEnumeration e = attrs.getAll();
                while (e.hasMoreElements()) {
                    Attribute attr = (Attribute) e.nextElement();
                    // When there are more than one attr in a LDAP entry the recorded entry name is the DN + attr. name
                    // For example the monitorOpInitiated and monitorOpCompleted attrs.
                    // There might be entries in the result set that do not contain any of the attributes to return 
                    if (Arrays.asList(attributesToReturn).contains(attr.getID())) {
                        String entryName = attrs.size() == 1 ? sr.getName() : sr.getName() + "_" + attr.getID();
                        receiver.recordLdapEntry(entryName, Double.parseDouble((String)attr.get()), attr.getID(), sr.getName() + "_"  + attr.getID());
    /*                     logger.log(Level.FINE, "LDAP entry info: DN:" + entryName + 
                                                " attr. name: " + attr.getID() + 
                                                " value: " + attr.get().toString()); 
    */
                    }

                }
                num_entries += 1;
            }
            logger.log(Level.FINE,"Scraped " + num_entries + " counters");
        } catch (Exception e) {
            logger.log(Level.FINE,"error in scrapeBackMonitorData" + e);
        }
        
    }

    /**
     * For debugging.
     */
    private static void logScrape(String name, String msg) {
        logger.log(Level.FINE, "scrape: '" + name + "': " + msg);
    }

    private static class StdoutWriter implements LdapReceiver {
        public void recordLdapEntry(
            String entryName,
            Number counterValue,
            String attrName,
            String attrDescription) {
            System.out.println("LDAP entry info: DN:" + entryName + 
                               " attr. name: " + attrName + 
                               " value: " + counterValue.toString());
        }
    }

    /**
     * Convenience function to run standalone.
     */
    public static void main(String[] args) throws Exception {
      List<String> objectNames = new LinkedList<String>();
      objectNames.add(null);
      if (args.length >= 3){
            new LdapScraper(args[0], args[1], args[2], false, objectNames, new LinkedList<String>(), new StdoutWriter()).doScrape();
        }
      else if (args.length > 0){
          new LdapScraper(args[0], "", "", false, objectNames, new LinkedList<String>(), new StdoutWriter()).doScrape();
      }
      else {
          new LdapScraper("", "", "", false, objectNames, new LinkedList<String>(), new StdoutWriter()).doScrape();
      }
    }
}

