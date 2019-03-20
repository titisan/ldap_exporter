package com.titisan.ldap;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;


public class LdapScraper {
    private static final String[] defatulAttributesToReturn= {"monitorCounter", "monitorOpInitiated", "monitorOpCompleted", "monitoredInfo" };
    private static final Logger logger = Logger.getLogger(LdapScraper.class.getName());;
    
    public static interface LdapReceiver {
        void recordLdapEntry(
            String entryName,
            Number counterValue,
            String attrName,
            String attrDescription);
    }

    private LdapReceiver receiver;
    private String ldapUrl;
    private String baseDn;
    private String username;
    private String password;
    private List<String> whitelistEntryNames, blacklistEntryNames, extraAttrsToReturn;

    public LdapScraper(String ldapUrl, String username, String password, String baseDN, List<String> whitelistEntryNames, List<String> blacklistEntryNames, List<String> extraAttrsToReturn, LdapReceiver receiver) {
        this.ldapUrl = ldapUrl;
        this.receiver = receiver;
        this.username = username;
        this.password = password;
        this.baseDn = baseDN;
        this.whitelistEntryNames = whitelistEntryNames;
        this.blacklistEntryNames = blacklistEntryNames;
        this.extraAttrsToReturn = extraAttrsToReturn;
    }

    /**
      * Get a list of attributes on ldapUrl and scrape their values.
      *
      * Values are passed to the receiver in a single thread.
      */
    public void doScrape() throws Exception {
        LdapContext dirConn = null;
        try {
            Hashtable<String,Object> environment = new Hashtable<String,Object>();
            environment.put(Context.PROVIDER_URL, ldapUrl);
            environment.put(Context.REFERRAL, "ignore");
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            environment.put("com.sun.jndi.ldap.connect.timeout", "5000");
            environment.put("java.naming.ldap.version", "3");
            if (username != null && username.length() != 0 && password != null && password.length() != 0) {
                environment.put(Context.SECURITY_AUTHENTICATION, "simple");
                environment.put(Context.SECURITY_PRINCIPAL, username);
                environment.put(Context.SECURITY_CREDENTIALS, password);
            }
            Control[] connCtls = new Control[0];
            dirConn = new InitialLdapContext(environment, connCtls);
            dirConn.reconnect(connCtls);
            SearchControls ctls = new SearchControls();
            ctls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            try {
               List<String> attributesToReturn = new ArrayList<String>( Arrays.asList(defatulAttributesToReturn) );
               for (String extraAttr : extraAttrsToReturn) {
                  // Add configured extra attributes to return, if any.
                  attributesToReturn.add(extraAttr);
               }
               ctls.setReturningAttributes(attributesToReturn.toArray(new String[] {} ));
            } catch (Exception e) {
               logger.log(Level.FINE,"Error in doScrape adding extra attributes to return." + e);
            }
           
            String filterStr = null;
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
                filterStr = "(objectClass=*)";
            }

            long start = System.nanoTime();
            NamingEnumeration<SearchResult> searchResult = dirConn.search(baseDn, filterStr, ctls);
            logger.fine("TIME: " + (System.nanoTime() - start) + " ns for reading " + baseDn + " data");
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
                    //if (Arrays.asList(attributesToReturn).contains(attr.getID())) {
                        String entryName = attrs.size() == 1 ? sr.getName() : sr.getName() + "_" + attr.getID();
                        try {
                           Double value = Double.valueOf((String)attr.get());
                           receiver.recordLdapEntry(entryName, value, attr.getID(), sr.getName() + "_"  + attr.getID());
                           logger.log(Level.FINE, "LDAP entry info: DN:" + entryName + 
                                                   " attr. name: " + attr.getID() + 
                                                   " value: " + attr.get().toString()); 
                        } catch (NumberFormatException numformatexcep) {
                           //logger.log(Level.FINE,"not a numeric metric: " + entryName);
                        }
                    //}
                }
                num_entries += 1;
            }
            logger.log(Level.FINE,"Scraped " + num_entries + " counters");
        } catch (Exception e) {
            logger.log(Level.FINE,"error in scrapeBackMonitorData" + e);
        }
        
    }

    private static class StdoutWriter implements LdapReceiver {
        public void recordLdapEntry(
            String entryName,
            Number counterValue,
            String attrName,
            String attrDescription) {
            System.out.println("LDAP entry info: DN:" + entryName + 
                               " attr. name: " + attrName + 
                               " value: " + counterValue.toString() +
                               " attr. description: " + attrDescription);
        }
    }

    /**
     * Convenience function to run standalone.
     */
    public static void main(String[] args) throws Exception {
        // arguments ldapUrl username password baseDN
        if (args.length >= 4){
                new LdapScraper(args[0], args[1], args[2],  args[3], new LinkedList<String>(), new LinkedList<String>(), new LinkedList<String>(), new StdoutWriter()).doScrape();
            }
        else if (args.length > 0){
            new LdapScraper(args[0], "", "",  "cn=Monitor", new LinkedList<String>(), new LinkedList<String>(), new LinkedList<String>(),new StdoutWriter()).doScrape();
        }
        else {
            new LdapScraper("ldap://127.0.0.1:389", "", "",  "cn=Monitor",new LinkedList<String>(), new LinkedList<String>(), new LinkedList<String>(),new StdoutWriter()).doScrape();
        }
    }
}

