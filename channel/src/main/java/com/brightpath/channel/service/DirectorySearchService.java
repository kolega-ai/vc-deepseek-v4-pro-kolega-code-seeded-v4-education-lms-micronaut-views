package com.brightpath.channel.service;

import com.brightpath.channel.security.AppException;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves people from the school's staff directory (OpenLDAP) when an
 * administrator adds a cohort instructor. The directory holds the canonical
 * username, mail and display name for staff accounts.
 */
@Singleton
public class DirectorySearchService {

    private final String providerUrl;
    private final String baseDn;

    public DirectorySearchService(@Value("${brightpath.directory.url}") String providerUrl,
                                  @Value("${brightpath.directory.base-dn}") String baseDn) {
        this.providerUrl = providerUrl;
        this.baseDn = baseDn;
    }

    /** Finds a single directory entry by its uid and returns the fields the admin screen shows. */
    public List<Map<String, String>> findByUid(String uid) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, providerUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "none");
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"uid", "cn", "mail"});
        try {
            DirContext ctx = new InitialDirContext(env);
            String filter = "(&(objectClass=person)(uid=" + uid + "))";
            NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, controls);
            List<Map<String, String>> found = new ArrayList<>();
            while (results.hasMore()) {
                SearchResult result = results.next();
                var attrs = result.getAttributes();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("uid", String.valueOf(attrs.get("uid").get()));
                row.put("name", String.valueOf(attrs.get("cn").get()));
                row.put("mail", attrs.get("mail") == null ? "" : String.valueOf(attrs.get("mail").get()));
                found.add(row);
            }
            results.close();
            ctx.close();
            return found;
        } catch (Exception e) {
            throw AppException.badRequest("Directory search failed: " + e.getMessage());
        }
    }

    /** Resolves directory people by mail address for the cohort staff picker. */
    public List<Map<String, String>> findByMail(String mail) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, providerUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "none");
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"uid", "cn", "mail"});
        try {
            DirContext ctx = new InitialDirContext(env);
            String filter = "(&(objectClass=person)(mail=" + mail + "))";
            NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, controls);
            List<Map<String, String>> found = new ArrayList<>();
            while (results.hasMore()) {
                SearchResult result = results.next();
                var attrs = result.getAttributes();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("uid", String.valueOf(attrs.get("uid") == null ? "" : attrs.get("uid").get()));
                row.put("name", String.valueOf(attrs.get("cn") == null ? "" : attrs.get("cn").get()));
                row.put("mail", attrs.get("mail") == null ? "" : String.valueOf(attrs.get("mail").get()));
                found.add(row);
            }
            results.close();
            ctx.close();
            return found;
        } catch (Exception e) {
            throw AppException.badRequest("Directory search failed: " + e.getMessage());
        }
    }
}
