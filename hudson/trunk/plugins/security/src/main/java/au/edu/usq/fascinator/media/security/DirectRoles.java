/*
 * The Fascinator - DiReCt Roles plugin
 * Copyright (C) 2010 University of Southern Queensland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package au.edu.usq.fascinator.media.security;

import au.edu.usq.fascinator.api.PluginDescription;
import au.edu.usq.fascinator.api.roles.Roles;
import au.edu.usq.fascinator.api.roles.RolesException;
import au.edu.usq.fascinator.common.JsonSimple;
import au.edu.usq.fascinator.common.JsonSimpleConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin to query USQ's Digital Resource Collection (DiReCt) for course
 * information useful as Fascinator Roles.
 *
 * @author Greg Pendlebury
 */
public class DirectRoles implements Roles {
    /** Logging */
    private final Logger log = LoggerFactory.getLogger(DirectRoles.class);

    /** DiReCt interface URL */
    private final String DIRECT_URL_PATH =
        "/services/tf_integration/securitygroups.php?username=";

    /** DiReCt Server */
    private String server;

    /** Cache Expiry - 10 minutes */
    private static int CACHE_EXPIRY = 10 * 60 * 1000;

    /** Cache - Timestamps */
    private Map<String, Long> cacheTimes;

    /** Cache - List of roles */
    private Map<String, List<String>> cacheRoles;

    /**
     * Get the ID of the plugin.
     *
     * @return String The plugin ID
     */
    @Override
    public String getId() {
        return "direct";
    }

    /**
     * Get the name of the plugin.
     *
     * @return String The plugin name
     */
    @Override
    public String getName() {
        return "DiReCt Roles";
    }

    /**
     * Gets a PluginDescription object relating to this plugin.
     *
     * @return a PluginDescription
     */
    @Override
    public PluginDescription getPluginDetails() {
        return new PluginDescription(this);
    }

    /**
     * Initialize the plugin from a string of JSON
     *
     * @param jsonString A string containing JSON configuration
     * @throws RolesException if there are errors
     */
    @Override
    public void init(String jsonString) throws RolesException {
        try {
            JsonSimple config = new JsonSimple(new ByteArrayInputStream(
                    jsonString.getBytes("UTF-8")));
            setConfig(config);
        } catch (UnsupportedEncodingException e) {
            throw new RolesException(e);
        } catch (IOException e) {
            throw new RolesException(e);
        }
    }

    /**
     * Initialize the plugin from a file containing JSON
     *
     * @param jsonFile A file containing JSON configuration
     * @throws RolesException if there are errors
     */
    @Override
    public void init(File jsonFile) throws RolesException {
        try {
            JsonSimple config = new JsonSimple(jsonFile);
            setConfig(config);
        } catch (IOException ioe) {
            throw new RolesException(ioe);
        }
    }

    /**
     * Perform the true initialization now that the configuration has been
     * normalized/instantiated.
     *
     * @param config An instantiated configuration object
     * @throws RolesException if there are errors
     */
    public void setConfig(JsonSimple config) throws RolesException {
        // Server address
        server = config.getString(null, "roles", "direct", "server");
        if (server == null) {
            throw new RolesException("No DiReCt server provided!");
        }

        // Caching
        cacheTimes = new HashMap();
        cacheRoles = new HashMap();
    }

    /**
     * Shutdown the plugin.
     *
     * @throws RolesException if there are errors
     */
    @Override
    public void shutdown() throws RolesException {
    }

    /**
     * Find and return all roles this user has.
     *
     * @param username The username of the user.
     * @return An array of role names (String).
     */
    @Override
    public String[] getRoles(String username) {
        // Sanity check
        if (username == null || username.equals("")) {
            return new String[0];
        }

        // Check the cache
        List<String> cachedRoles = getCache(username);
        if (cachedRoles != null) {
            return cachedRoles.toArray(new String[0]);
        }

        // Query Direct
        HttpClient client = new HttpClient();
        String response = null;
        try {
            URL url = new URL(server + DIRECT_URL_PATH + username);
            GetMethod get = new GetMethod(url.toString());
            int status = client.executeMethod(get);
            if (status != 200) {
                log.error("Error connecting to DiReCt! Status '{}' : {}",
                        status, get.getResponseBodyAsString());
                return new String[0];
            }
            response = get.getResponseBodyAsString();
        } catch (IOException ex) {
            log.error("Error connecting to DiReCt: ", ex);
            return new String[0];
        }

        // Modify Repsonse. Whilst technically valid JSON, the response
        // doesn't suit the Xpath access the JSON library uses. Wrap it.
        response = "{\"roles\": " + response + "}";

        // Parse response
        List<String> roles = new ArrayList();
        try {
            JsonSimpleConfig data = new JsonSimpleConfig(response);
            for (String object : data.getStringList("roles")) {
                roles.add(object);
            }
        } catch (IOException ex) {
            log.error("Error parsing DiReCt response : ", ex);
            return new String[0];
        }

        // Cache and return
        cache(username, new HashSet(roles));
        return roles.toArray(new String[0]);
    }

    /**
     * Retrieve a list of roles for the given username from the cache if the
     * cache expiry has not passed.
     *
     * @param username: The username to retrieve from the cache
     * @return List<String>: A list of roles from the cache, possibly empty,
     * NULL if expired or not present
     */
    private List<String> getCache(String username) {
        long now = new Date().getTime();
        if (cacheTimes.containsKey(username)) {
            long cached = cacheTimes.get(username);
            long age = now - cached;
            if (age > CACHE_EXPIRY) {
                cacheTimes.remove(username);
                cacheRoles.remove(username);
                return null;
            } else {
                return cacheRoles.get(username);
            }
        }
        return null;
    }

    /**
     * Add the given roles to the cache under the provided username.
     *
     * @param username: The username to use as the index in the cache
     * @param List<String>: A list of roles to add to the cache, possibly empty
     */
    private void cache(String username, Set<String> roles) {
        // Turn our set into a list
        List<String> list = new ArrayList();
        for (String role : roles) {
            list.add(role);
        }
        // Now cache
        long now = new Date().getTime();
        cacheTimes.put(username, now);
        cacheRoles.put(username, list);
    }

    /**
     * Returns a list of users who have a particular role.
     *
     * @param role The role to search for.
     * @return An array of usernames (String) that have that role.
     */
    @Override
    public String[] getUsersInRole(String role) {
        return new String[0];
    }

    /**
     * Method for testing if the implementing plugin allows
     * the creation, deletion and modification of roles.
     *
     * @return true/false reponse.
     */
    @Override
    public boolean supportsRoleManagement() {
        return false;
    }

    /**
     * Assign a role to a user.
     *
     * @param username The username of the user.
     * @param newrole The new role to assign the user.
     * @throws RolesException if there was an error during assignment.
     */
    @Override
    public void setRole(String username, String newrole)
            throws RolesException {
        throw new RolesException(
                "Role management is not supported by this plugin.");
    }

    /**
     * Remove a role from a user.
     *
     * @param username The username of the user.
     * @param oldrole The role to remove from the user.
     * @throws RolesException if there was an error during removal.
     */
    @Override
    public void removeRole(String username, String oldrole)
            throws RolesException {
        throw new RolesException(
                "Role management is not supported by this plugin.");
    }

    /**
     * Create a role.
     *
     * @param rolename The name of the new role.
     * @throws RolesException if there was an error creating the role.
     */
    @Override
    public void createRole(String rolename)
            throws RolesException {
        throw new RolesException(
                "Role management is not supported by this plugin.");
    }

    /**
     * Delete a role.
     *
     * @param rolename The name of the role to delete.
     * @throws RolesException if there was an error during deletion.
     */
    @Override
    public void deleteRole(String rolename) throws RolesException {
        throw new RolesException(
                "Role management is not supported by this plugin.");
    }

    /**
     * Rename a role.
     *
     * @param oldrole The name role currently has.
     * @param newrole The name role is changing to.
     * @throws RolesException if there was an error during rename.
     */
    @Override
    public void renameRole(String oldrole, String newrole)
            throws RolesException {
        throw new RolesException(
                "Role management is not supported by this plugin.");
    }

    /**
     * Returns a list of roles matching the search.
     *
     * @param search The search string to execute.
     * @return An array of role names that match the search.
     * @throws RolesException if there was an error searching.
     */
    @Override
    public String[] searchRoles(String search) throws RolesException {
        throw new RolesException(
                "Role searching is not supported by this plugin," +
                " query is by username");
    }
}
