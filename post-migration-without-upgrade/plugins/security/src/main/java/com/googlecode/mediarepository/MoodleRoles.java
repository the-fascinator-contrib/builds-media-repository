/*
 * The Fascinator - Moodle Roles plugin
 * Copyright (C) 2010-2011 University of Southern Queensland
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
package com.googlecode.mediarepository;

import com.googlecode.fascinator.api.PluginDescription;
import com.googlecode.fascinator.api.roles.Roles;
import com.googlecode.fascinator.api.roles.RolesException;
import com.googlecode.fascinator.common.BasicHttpClient;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.sax.SafeSAXReader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.methods.GetMethod;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin to query moodle for course information useful as Fascinator Roles.
 * 
 * @author Greg Pendlebury
 */
public class MoodleRoles implements Roles {
    /** Logging */
    private final Logger log = LoggerFactory.getLogger(MoodleRoles.class);

    /** Moodle interface URL */
    private final String MOODLE_URL_BASE = "https://usqstudydesk.usq.edu.au/usq/iface/courselist.php?username="
            + "{username}&limitto=recent,future";

    /** Moodle interface URL */
    private final String COMMUNITY_URL_BASE = "https://community.usq.edu.au/usq/iface/courselist.php?username="
            + "{username}&limitto=recent,future";

    /** XML parsing **/
    private SafeSAXReader reader;

    /** JDBC Driver */
    private static String DERBY_DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";

    /** Connection string prefix */
    private static String DERBY_PROTOCOL = "jdbc:derby:";

    /** Moodle database name */
    private static String MOODLE_DATABASE = "tfMoodle";

    /** Course table */
    private static String COURSE_TABLE = "course";

    /** PeopleSoft Course table */
    private static String PS_COURSE_TABLE = "ps_course";

    /** Database home directory */
    private String derbyHome;

    /** Database connection */
    private Connection conn;

    /** SQL Statement */
    private Statement sql;

    /** Result set */
    private ResultSet result;

    /** Insert statement */
    private PreparedStatement insert;

    /** Insert statement - Peoplesoft */
    private PreparedStatement insertPS;

    /** Select statement */
    private PreparedStatement select;

    /** Select statement - Peoplesoft */
    private PreparedStatement selectPS;

    /** Update statement */
    private PreparedStatement update;

    /** Update statement - Peoplesoft */
    private PreparedStatement updatePS;

    /** Cache Expiry - 10 minutes */
    private static int CACHE_EXPIRY = 10 * 60 * 1000;

    /** Cache - Timestamps */
    private Map<String, Long> cacheTimes;

    /** Cache - List of roles */
    private Map<String, List<String>> cacheRoles;

    /** Pattern matcher for Peoplesoft courses */
    private Matcher psPatternMatch;

    /**
     * Get the ID of the plugin.
     * 
     * @return String The plugin ID
     */
    @Override
    public String getId() {
        return "moodle";
    }

    /**
     * Get the name of the plugin.
     * 
     * @return String The plugin name
     */
    @Override
    public String getName() {
        return "Moodle Roles";
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
        // XML Parser
        reader = new SafeSAXReader();

        // Caching
        cacheTimes = new HashMap();
        cacheRoles = new HashMap();

        // Peoplesoft course pattern matching
        Pattern p = Pattern.compile("([A-Z]{3,5}[0-9]{4})_([0-9]{4})_([0-9])$");
        psPatternMatch = p.matcher("");

        // Find our database home directory
        derbyHome = config.getString(null, "roles", "moodle", "derbyHome");
        if (derbyHome == null) {
            throw new RolesException("Database home not specified!");

        } else {
            // Establish its validity and existance, create if necessary
            File file = new File(derbyHome);
            if (file.exists()) {
                if (!file.isDirectory()) {
                    throw new RolesException("Database home '" + derbyHome
                            + "' is not a directory!");
                }
            } else {
                file.mkdirs();
                if (!file.exists()) {
                    throw new RolesException("Database home '" + derbyHome
                            + "' does not exist and could not be created!");
                }
            }
        }
        // Set the system property to match, the DriverManager will look here
        System.setProperty("derby.system.home", derbyHome);

        // Load the JDBC driver
        try {
            Class.forName(DERBY_DRIVER).newInstance();
        } catch (Exception ex) {
            log.error("Driver load failed: ", ex);
            throw new RolesException("Driver load failed: ", ex);
        }

        // Database prep work
        Properties props = new Properties();
        try {
            // Establish a database connection, create the database if needed
            conn = DriverManager.getConnection(DERBY_PROTOCOL + MOODLE_DATABASE
                    + ";create=true", props);
            sql = conn.createStatement();

            // Look for our tables
            checkTable(COURSE_TABLE);
            checkTable(PS_COURSE_TABLE);
        } catch (SQLException ex) {
            log.error("Error during database preparation:", ex);
            throw new RolesException("Error during database preparation:", ex);
        }
        log.debug("Derby security database online!");
    }

    /**
     * Shutdown the plugin.
     * 
     * @throws RolesException if there are errors
     */
    @Override
    public void shutdown() throws RolesException {
        // Release all our queries
        close(sql);
        close(insert);
        close(insertPS);
        close(select);
        close(selectPS);
        close(update);
        close(updatePS);

        // Derby can only be shutdown from one thread,
        //    we'll catch errors from the rest.
        String threadedShutdownMessage = DERBY_DRIVER
                + " is not registered with the JDBC driver manager";
        try {
            // Tell the database to close
            DriverManager.getConnection(DERBY_PROTOCOL + ";shutdown=true");
            // Shutdown just this database (but not the engine)
            //DriverManager.getConnection(DERBY_PROTOCOL + SECURITY_DATABASE +
            //        ";shutdown=true");
        } catch (SQLException ex) {
            // These test values are used if the engine is NOT shutdown
            //if (ex.getErrorCode() == 45000 &&
            //        ex.getSQLState().equals("08006")) {

            // Valid response
            if (ex.getErrorCode() == 50000 && ex.getSQLState().equals("XJ015")) {
                // Error response
            } else {
                // Make sure we ignore simple thread issues
                if (!ex.getMessage().equals(threadedShutdownMessage)) {
                    log.error("Error during database shutdown:", ex);
                    throw new RolesException("Error during database shutdown:",
                            ex);
                }
            }
        } finally {
            try {
                // Close our connection
                if (conn != null) {
                    conn.close();
                    conn = null;
                }
            } catch (SQLException ex) {
                log.error("Error closing connection:", ex);
                throw new RolesException("Error closing connection:", ex);
            }
        }
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

        // Query Moodle USQStudyDesk
        //  - force to lowercase (USQ Moodle requirement)
        String url = MOODLE_URL_BASE.replace("{username}",
                username.toLowerCase());
        BasicHttpClient client = new BasicHttpClient(url);
        GetMethod get = new GetMethod(url);
        String response = null;
        try {
            int status = client.executeMethod(get);
            if (status != 200) {
                log.error("Error connecting to moodle USQStudyDesk! Status '{}' : {}",
                        status, get.getResponseBodyAsString());
                return new String[0];
            }
            response = get.getResponseBodyAsString();
        } catch (IOException ex) {
            log.error("Error connecting to moodle USQStudyDesk: ", ex);
            return new String[0];
        }

        // Parse response
        Map<String, String> roles = new LinkedHashMap();
        try {
            Document doc = loadDocument(response);
            List<Node> nodes = doc.selectNodes("//course");
            for (Node node : nodes) {
                String id = node.selectSingleNode("./@id").getText();
                String name = node.selectSingleNode("./fullname").getText();
                Node psNode = node.selectSingleNode("./shortname");
                String ps = "";
                if (psNode != null) {
                    ps = psNode.getText();
                }
                roles.put(id, name);
                database(id, name, ps);
            }
        } catch (DocumentException ex) {
            log.error("Error parsing moodle USQStudyDesk response : ", ex);
            return new String[0];
        }

        // Query Moodle Community
        String communityUrl = COMMUNITY_URL_BASE.replace("{username}",
                username.toLowerCase());
        client = new BasicHttpClient(communityUrl);
        get = new GetMethod(communityUrl);
        response = null;
        try {
            int status = client.executeMethod(get);
            if (status != 200) {
                log.error("Error connection to moodle Community Status '{}' : {}",
                        status, get.getResponseBodyAsString());
                if (!roles.isEmpty()) {
                    cache(username, roles.keySet());
                    return roles.keySet().toArray(new String[0]);
                }
                return new String [0];
            }
            response = get.getResponseBodyAsString();
        } catch (IOException ex) {
            log.error("Error connection to moodle Community: ", ex);
            if (!roles.isEmpty()) {
                cache(username, roles.keySet());
                return roles.keySet().toArray(new String[0]);
            }
            return new String [0];
        }
        
        // Parse response and add to roles map
        try {
            Document doc = loadDocument(response);
            List<Node> nodes = doc.selectNodes("//course");
            for (Node node : nodes) {
                String id = node.selectSingleNode("./@id").getText();
                String name = node.selectSingleNode("./fullname").getText();
                Node psNode = node.selectSingleNode("./shortname");
                String ps = "";
                if (psNode != null) {
                    ps = psNode.getText();
                }
                roles.put(id, name);
                database(id, name, ps);
            }
        } catch (DocumentException ex) {
            log.error("Error parsing moddle Community response : ", ex);
            if (!roles.isEmpty()) {
                cache(username, roles.keySet());
                return roles.keySet().toArray(new String[0]);
            }
            return new String[0];
        }

        // Cache and return
        cache(username, roles.keySet());
        return roles.keySet().toArray(new String[0]);
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
     * Turn an XML string into a parsed XML document
     * 
     * @param document: String to parse
     * @return Document after parsing
     * @throws DocumentException if there are encoding or parse errors
     */
    private Document loadDocument(String document) throws DocumentException {
        try {
            byte[] bytes = document.getBytes("UTF-8");
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            return reader.loadDocumentFromStream(in);
        } catch (UnsupportedEncodingException ex) {
            throw new DocumentException("Error in UTF-8 encoding: ", ex);
        }
    }

    /**
     * Store or update the name of the course for use later in user interfaces.
     * 
     * @param id The course ID to store.
     * @param name The name of the course.
     * @param peoplesoft The peoplesoft details of the course.
     * @throws SQLException if there were database errors during storage
     */
    private void database(String id, String name, String peoplesoft) {
        try {
            // Basic names
            if (search(id)) {
                update(id, name);
            } else {
                store(id, name);
            }

            // Peoplesoft data
            psPatternMatch.reset(peoplesoft);
            if (psPatternMatch.matches()) {
                String course = psPatternMatch.group(1);
                String year = psPatternMatch.group(2);
                String semester = psPatternMatch.group(3);
                if (searchPS(id)) {
                    updatePS(id, course, year, semester);
                } else {
                    storePS(id, course, year, semester);
                }
            }
        } catch (SQLException ex) {
            log.error("Error during database access: ", ex);
        }
    }

    /**
     * Store the name of the course.
     * 
     * @param id The course ID to store.
     * @param name The name of the course.
     * @throws SQLException if there were database errors during storage
     */
    private void store(String id, String name) throws SQLException {
        // First run
        if (insert == null) {
            insert = conn.prepareStatement("INSERT INTO " + COURSE_TABLE
                    + " (id, name) VALUES (?, ?)");
        }

        // Prepare and execute
        insert.setString(1, id);
        insert.setString(2, name);
        insert.executeUpdate();
    }

    /**
     * Update the name of the course already in storage.
     * 
     * @param id The course ID to store.
     * @param name The name of the course.
     * @throws SQLException if there were database errors during storage
     */
    private void update(String id, String name) throws SQLException {
        // First run
        if (update == null) {
            update = conn.prepareStatement("UPDATE " + COURSE_TABLE
                    + " SET name = ? WHERE id = ?");
        }

        // Prepare and execute
        update.setString(1, name);
        update.setString(2, id);
        update.executeUpdate();
    }

    /**
     * Search for a course id in storage
     * 
     * @param id The course ID to find
     * @return boolean True if found, False otherwise
     * @throws SQLException if there were database errors during the search
     */
    private boolean search(String id) throws SQLException {
        // First run
        if (select == null) {
            select = conn.prepareStatement("SELECT count(*) as total FROM "
                    + COURSE_TABLE + " WHERE id = ?");
        }

        // Prepare and execute
        select.setString(1, id);
        result = select.executeQuery();

        // Build response
        boolean response = false;
        if (result.next()) {
            if (result.getInt("total") == 1) {
                response = true;
            }
        }
        close(result);
        return response;
    }

    /**
     * Search for a course id in storage
     * 
     * @param id The course ID to find
     * @return boolean True if found, False otherwise
     * @throws SQLException if there were database errors during the search
     */
    private boolean searchPS(String id) throws SQLException {
        // First run
        if (selectPS == null) {
            selectPS = conn.prepareStatement("SELECT count(*) as total FROM "
                    + PS_COURSE_TABLE + " WHERE id = ?");
        }

        // Prepare and execute
        selectPS.setString(1, id);
        result = selectPS.executeQuery();

        // Build response
        boolean response = false;
        if (result.next()) {
            if (result.getInt("total") == 1) {
                response = true;
            }
        }
        close(result);
        return response;
    }

    /**
     * Store the Peoplesoft course details
     * 
     * @param id The course ID to store.
     * @param course The course code
     * @param year The year of offer
     * @param semester The semester of the year
     * @throws SQLException if there were database errors during storage
     */
    private void storePS(String id, String course, String year, String semester)
            throws SQLException {
        // First run
        if (insertPS == null) {
            insertPS = conn
                    .prepareStatement("INSERT INTO "
                            + PS_COURSE_TABLE
                            + " (id, psCourse, psYear, psSemester) VALUES (?, ?, ?, ?)");
        }

        // Prepare and execute
        insertPS.setString(1, id);
        insertPS.setString(2, course);
        insertPS.setString(3, year);
        insertPS.setString(4, semester);
        insertPS.executeUpdate();
    }

    /**
     * Update the Peoplesoft course details already in storage.
     * 
     * @param id The course ID to store.
     * @param course The course code
     * @param year The year of offer
     * @param semester The semester of the year
     * @throws SQLException if there were database errors during storage
     */
    private void updatePS(String id, String course, String year, String semester)
            throws SQLException {
        // First run
        if (updatePS == null) {
            updatePS = conn
                    .prepareStatement("UPDATE "
                            + PS_COURSE_TABLE
                            + " SET psCourse = ?, psYear = ?, psSemester = ? WHERE id = ?");
        }

        // Prepare and execute
        updatePS.setString(1, course);
        updatePS.setString(2, year);
        updatePS.setString(3, semester);
        updatePS.setString(4, id);
        updatePS.executeUpdate();
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
     * Method for testing if the implementing plugin allows the creation,
     * deletion and modification of roles.
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
    public void setRole(String username, String newrole) throws RolesException {
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
    public void createRole(String rolename) throws RolesException {
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
                "Role searching is not supported by this plugin,"
                        + " query is by username");
    }

    /**
     * Check for the existence of a table and arrange for its creation if not
     * found.
     * 
     * @param table The table to look for and create.
     * @throws SQLException if there was an error.
     */
    private void checkTable(String table) throws SQLException {
        boolean tableFound = findTable(table);

        // Create the table if we couldn't find it
        if (!tableFound) {
            log.debug("Table '{}' not found, creating now!", table);
            createTable(table);

            // Double check it was created
            if (!findTable(table)) {
                log.error("Unknown error creating table '{}'", table);
                throw new SQLException("Could not find or create table '"
                        + table + "'");
            }
        }
    }

    /**
     * Check if the given table exists in the database.
     * 
     * @param table The table to look for
     * @return boolean flag if the table was found or not
     * @throws SQLException if there was an error accessing the database
     */
    private boolean findTable(String table) throws SQLException {
        boolean tableFound = false;
        DatabaseMetaData meta = conn.getMetaData();
        result = (ResultSet) meta.getTables(null, null, null, null);
        while (result.next() && !tableFound) {
            if (result.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                tableFound = true;
            }
        }
        close(result);
        return tableFound;
    }

    /**
     * Create the given table in the database.
     * 
     * @param table The table to create
     * @throws SQLException if there was an error during creation, or an unknown
     * table was specified.
     */
    private void createTable(String table) throws SQLException {
        if (table.equals(COURSE_TABLE)) {
            sql.execute("CREATE TABLE " + COURSE_TABLE
                    + "(id VARCHAR(255) NOT NULL, "
                    + "name VARCHAR(255) NOT NULL, " + "PRIMARY KEY (id))");
            return;
        }
        if (table.equals(PS_COURSE_TABLE)) {
            sql.execute("CREATE TABLE " + PS_COURSE_TABLE
                    + "(id VARCHAR(255) NOT NULL, "
                    + "psCourse VARCHAR(10) NOT NULL, "
                    + "psYear VARCHAR(4) NOT NULL, "
                    + "psSemester VARCHAR(2) NOT NULL, " + "PRIMARY KEY (id))");
            return;
        }
        throw new SQLException("Unknown table '" + table + "' requested!");
    }

    /**
     * Attempt to close a ResultSet. Basic wrapper for exception catching and
     * logging
     * 
     * @param resultSet The ResultSet to try and close.
     */
    private void close(ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException ex) {
                log.error("Error closing result set: ", ex);
            }
        }
        resultSet = null;
    }

    /**
     * Attempt to close a Statement. Basic wrapper for exception catching and
     * logging
     * 
     * @param statement The Statement to try and close.
     */
    private void close(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ex) {
                log.error("Error closing statement: ", ex);
            }
        }
        statement = null;
    }
}
