/*
 * The Fascinator - USQ Course Access Control plugin
 * Copyright (C) 2008-2010 University of Southern Queensland
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
import au.edu.usq.fascinator.api.access.AccessControl;
import au.edu.usq.fascinator.api.access.AccessControlException;
import au.edu.usq.fascinator.api.access.AccessControlSchema;
import au.edu.usq.fascinator.api.authentication.AuthenticationException;
import au.edu.usq.fascinator.common.JsonSimple;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.lang.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An access control plugin to model the USQ course enrollment
 * data as it relates to security for integration with DiReCt.
 *
 * @author Greg Pendlebury
 */
public class CourseAccessControl implements AccessControl {
    /** Logging */
    private final Logger log =
            LoggerFactory.getLogger(CourseAccessControl.class);

    /** JDBC Driver */
    private static String DERBY_DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";

    /** Connection string prefix */
    private static String DERBY_PROTOCOL = "jdbc:derby:";

    /** Security database name */
    private static String SECURITY_DATABASE = "directSecurity";

    /** Records table */
    private static String RECORD_TABLE = "records";

    /** Roles table */
    private static String ROLE_TABLE = "roles";

    /** Schemas table */
    private static String SCHEMA_TABLE = "schemas";

    /** Database home directory */
    private String derbyHome;

    /** Database connection */
    private Connection conn;

    /** Map of SQL statements */
    private Map<String, PreparedStatement> statements;

    /**
     * Gets an identifier for this type of plugin. This should be a simple name
     * such as "file-system" for a storage plugin, for example.
     *
     * @return the plugin type id
     */
    @Override
    public String getId() {
        return "course";
    }

    /**
     * Gets a name for this plugin. This should be a descriptive name.
     *
     * @return the plugin name
     */
    @Override
    public String getName() {
        return "Course Access Control";
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
     * Initializes the plugin using the specified JSON String
     *
     * @param jsonString JSON configuration string
     * @throws PluginException if there was an error in initialization
     */
    @Override
    public void init(String jsonString) throws AccessControlException {
        try {
            JsonSimple config = new JsonSimple(new ByteArrayInputStream(
                    jsonString.getBytes("UTF-8")));
            setConfig(config);
        } catch (UnsupportedEncodingException e) {
            throw new AccessControlException(e);
        } catch (IOException e) {
            throw new AccessControlException(e);
        }
    }

    /**
     * Initializes the plugin using the specified JSON configuration
     *
     * @param jsonFile JSON configuration file
     * @throws AccessControlException if there was an error in initialization
     */
    @Override
    public void init(File jsonFile) throws AccessControlException {
        try {
            JsonSimple config = new JsonSimple(jsonFile);
            setConfig(config);
        } catch (IOException ioe) {
            throw new AccessControlException(ioe);
        }
    }

    /**
     * Initialization of Solr Access Control plugin
     *
     * @param config The configuration to use
     * @throws AuthenticationException if fails to initialize
     */
    private void setConfig(JsonSimple config) throws AccessControlException {
        statements = new HashMap();
        // Find our database home directory
        derbyHome = config.getString(null, "accesscontrol", "course", "derbyHome");
        if (derbyHome == null) {
            throw new AccessControlException("Database home not specified!");

        } else {
            // Establish its validity and existance, create if necessary
            File file = new File(derbyHome);
            if (file.exists()) {
                if (!file.isDirectory()) {
                    throw new AccessControlException("Database home '" +
                            derbyHome + "' is not a directory!");
                }
            } else {
                file.mkdirs();
                if (!file.exists()) {
                    throw new AccessControlException("Database home '" +
                            derbyHome +
                            "' does not exist and could not be created!");
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
            throw new AccessControlException("Driver load failed: ", ex);
        }

        // Database prep work
        Properties props = new Properties();
        try {
            // Establish a database connection, create the database if needed
            conn = DriverManager.getConnection(DERBY_PROTOCOL +
                    SECURITY_DATABASE + ";create=true", props);

            // Look for our tables
            checkTable(RECORD_TABLE);
            checkTable(ROLE_TABLE);
            checkTable(SCHEMA_TABLE);
        } catch (SQLException ex) {
            log.error("Error during database preparation:", ex);
            throw new AccessControlException(
                    "Error during database preparation:", ex);
        }
        log.debug("Derby security database online!");
    }

    /**
     * Shuts down the plugin
     *
     * @throws AccessControlException if there was an error during shutdown
     */
    @Override
    public void shutdown() throws AccessControlException {
        // Release all our queries
        for (String key : statements.keySet()) {
            close(statements.get(key));
        }

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
            if (ex.getErrorCode() == 50000 &&
                    ex.getSQLState().equals("XJ015")) {
            // Error response
            } else {
                // Make sure we ignore simple thread issues
                if (!ex.getMessage().equals(threadedShutdownMessage)) {
                    log.error("Error during database shutdown:", ex);
                    throw new AccessControlException(
                            "Error during database shutdown:", ex);
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
                throw new AccessControlException(
                        "Error closing connection:", ex);
            }
        }
    }

    /**
     * Return an empty security schema for the portal to investigate and/or
     * populate.
     *
     * @return An empty security schema
     */
    @Override
    public AccessControlSchema getEmptySchema() {
        return new CourseSchema();
    }

    /**
     * Get a list of schemas that have been applied to a record.
     *
     * @param recordId The record to retrieve information about.
     * @return A list of access control schemas, possibly zero length.
     * @throws AccessControlException if there was an error during retrieval.
     */
    @Override
    public List<AccessControlSchema> getSchemas(String recordId)
            throws AccessControlException {
        try {
            List<CourseSchema> currentList = selectSchemas(recordId);
            List<AccessControlSchema> schemas = new ArrayList();
            for (CourseSchema schema : currentList) {
                schemas.add(schema);
            }
            return schemas;
        } catch (Exception ex) {
            log.error("Error searching security database: ", ex);
            throw new AccessControlException(
                    "Error searching security database");
        }
    }

    /**
     * Apply/store a new security implementation. The schema will already have
     * a recordId as a property.
     *
     * @param newSecurity The new schema to apply.
     * @throws AccessControlException if storage of the schema fails.
     */
    @Override
    public void applySchema(AccessControlSchema newSecurity)
            throws AccessControlException {
        // Find the data we are about to add
        String recordId = newSecurity.getRecordId();
        if (recordId == null || recordId.equals("")) {
            throw new AccessControlException("No record provided by schema.");
        }
        String course = getFromSchema("course", newSecurity);
        String year = getFromSchema("year", newSecurity);
        String semester = getFromSchema("semester", newSecurity);

        // Retrieve current data
        List<CourseSchema> currentList;
        try {
            currentList = selectSchemas(recordId);
        } catch (Exception ex) {
            log.error("Error searching security database: ", ex);
            throw new AccessControlException(
                    "Error searching security database");
        }

        // Make sure the schema has not already been applied
        boolean found = false;
        for (CourseSchema schema : currentList) {
            if (schema.course.equals(course) &&  schema.year.equals(year) &&
                    schema.semester.equals(semester)) {
                found = true;
            }
        }
        if (found) {
            throw new AccessControlException(
                    "That schema you have tried to apply already has access" +
                    " to this record.");
        }

        // Add the new relationship to the database
        try {
            applySchema((CourseSchema) newSecurity);
            if (!checkRecord(recordId)) {
                newRecord(recordId);
            }
        } catch (Exception ex) {
            log.error("Error updating security database: ", ex);
            throw new AccessControlException(
                    "Error updating security database");
        }
    }

    /**
     * Remove a security implementation. The schema will already have
     * a recordId as a property.
     *
     * @param oldSecurity The schema to remove.
     * @throws AccessControlException if removal of the schema fails.
     */
    @Override
    public void removeSchema(AccessControlSchema oldSecurity)
            throws AccessControlException {
        // Find the data we are about to remove
        String recordId = oldSecurity.getRecordId();
        if (recordId == null || recordId.equals("")) {
            throw new AccessControlException("No record provided by schema.");
        }
        String course = getFromSchema("course", oldSecurity);
        String year = getFromSchema("year", oldSecurity);
        String semester = getFromSchema("semester", oldSecurity);

        // Retrieve current data
        List<CourseSchema> currentList;
        try {
            currentList = selectSchemas(recordId);
        } catch (Exception ex) {
            log.error("Error searching security database: ", ex);
            throw new AccessControlException(
                    "Error searching security database");
        }

        // Make sure the schema has actually been applied before we remove it
        boolean found = false;
        CourseSchema target = null;
        for (CourseSchema schema : currentList) {
            if (schema.course.equals(course) &&  schema.year.equals(year) &&
                    schema.semester.equals(semester)) {
                found = true;
                target = schema;
            }
        }
        if (!found) {
            throw new AccessControlException(
                    "That schema you have tried to remove is not currently" +
                    " applied to this record.");
        }

        // Remove from security database
        try {
            removeSchema(target);
        } catch (Exception ex) {
            log.error("Error updating security database: ", ex);
            throw new AccessControlException(
                    "Error updating security database");
        }
    }

    /**
     * A basic wrapper for getSchemas() to return just the roles of the schemas.
     * Useful during index and/or audit when this is the only data required.
     *
     * @param recordId The record to retrieve roles for.
     * @return A list of Strings containing role names.
     * @throws AccessControlException if there was an error during retrieval.
     */
    public String getFromSchema(String field, AccessControlSchema schema)
            throws AccessControlException {
        String result = schema.get(field);
        if (result == null || result.equals("")) {
            throw new AccessControlException(
                    "No '" + field + "' data provided by schema.");
        }
        return result;
    }

    /**
     * A basic wrapper for getSchemas() to return just the roles of the schemas.
     * Useful during index and/or audit when this is the only data required.
     *
     * @param recordId The record to retrieve roles for.
     * @return A list of Strings containing role names.
     * @throws AccessControlException if there was an error during retrieval.
     */
    @Override
    public List<String> getRoles(String recordId)
            throws AccessControlException {
        try {
            List<String> roles = selectRoles(recordId);
            if (roles.isEmpty()) {
                if (!checkRecord(recordId)) {
                    return null;
                }
            }
            return roles;
        } catch (SQLException ex) {
            log.error("Error searching security database: ", ex);
            throw new AccessControlException(
                    "Error searching security database");
        }
    }

    /**
     * Retrieve a list of possible field values for a given field if the plugin
     * supports this feature.
     *
     * @param field The field name.
     * @return A list of String containing possible values
     * @throws AccessControlException if the field doesn't exist or there
     *          was an error during retrieval
     */
    @Override
    public List<String> getPossibilities(String field)
            throws AccessControlException {
        throw new AccessControlException(
                "Not supported by this plugin. Use any freetext role name.");
    }

    /**
     * Check for the existence of a table and arrange for its creation if
     * not found.
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
                throw new SQLException(
                        "Could not find or create table '" + table + "'");
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
        ResultSet result = (ResultSet) meta.getTables(null, null, null, null);
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
     * @throws SQLException if there was an error during creation,
     *                      or an unknown table was specified.
     */
    private void createTable(String table) throws SQLException {
        Statement sql = conn.createStatement();
        if (table.equals(RECORD_TABLE)) {
            sql.execute(
                    "CREATE TABLE " + RECORD_TABLE +
                    "(recordId VARCHAR(255) NOT NULL, " +
                    "PRIMARY KEY (recordId))");
            close(sql);
            return;
        }
        if (table.equals(ROLE_TABLE)) {
            sql.execute(
                    "CREATE TABLE " + ROLE_TABLE +
                    "(recordId VARCHAR(255) NOT NULL, " +
                    "schemaId INTEGER NOT NULL, " +
                    "role VARCHAR(255) NOT NULL, " +
                    "PRIMARY KEY (recordId, schemaId, role))");
            close(sql);
            return;
        }
        if (table.equals(SCHEMA_TABLE)) {
            sql.execute(
                    "CREATE TABLE " + SCHEMA_TABLE +
                    "(schemaId INTEGER NOT NULL GENERATED ALWAYS AS " +
                    "IDENTITY (START WITH 1, INCREMENT BY 1), " +
                    "recordId VARCHAR(255) NOT NULL, " +
                    // Core offering data
                    "course VARCHAR(255) NOT NULL, " +
                    "courseYear VARCHAR(255) NOT NULL, " +
                    "semester VARCHAR(255) NOT NULL, " +
                    // Campus _ Mode
                    "too_ext CHAR(1) NOT NULL, " +
                    "too_onc CHAR(1) NOT NULL, " +
                    "too_www CHAR(1) NOT NULL, " +
                    "fra_ext CHAR(1) NOT NULL, " +
                    "fra_onc CHAR(1) NOT NULL, " +
                    "fra_www CHAR(1) NOT NULL, " +
                    "spr_ext CHAR(1) NOT NULL, " +
                    "spr_onc CHAR(1) NOT NULL, " +
                    "spr_www CHAR(1) NOT NULL, " +
                    "PRIMARY KEY (recordId, course, courseYear, semester))");
            close(sql);
            return;
        }
        close(sql);
        throw new SQLException("Unknown table '" + table + "' requested!");
    }

    private List<CourseSchema> selectSchemas(String recordId)
            throws SQLException {
        Map<String, CourseSchema> schemas = new HashMap();

        // Prepare and execute
        PreparedStatement select = prepare("selectSchemas",
                "SELECT a.*, b.role FROM " +
                    SCHEMA_TABLE + " a, " +
                    ROLE_TABLE + " b " +
                "WHERE a.recordId = ? " +
                "AND a.schemaId = b.schemaId " +
                "ORDER BY a.schemaId");
        select.setString(1, recordId);
        ResultSet result = select.executeQuery();

        // Build response
        while (result.next()) {
            String sId = Integer.toString(result.getInt("schemaId"));
            // Is this the first time we've seen this sId?
            if (!schemas.containsKey(sId)) {
                // Build a schema
                CourseSchema schema = new CourseSchema();
                schema.init(result.getString("recordId"));
                schema.set("id", sId);
                schema.set("course", result.getString("course"));
                schema.set("year", result.getString("courseYear"));
                schema.set("semester", result.getString("semester"));
                schema.set("too_ext", result.getString("too_ext"));
                schema.set("too_onc", result.getString("too_onc"));
                schema.set("too_www", result.getString("too_www"));
                schema.set("fra_ext", result.getString("fra_ext"));
                schema.set("fra_onc", result.getString("fra_onc"));
                schema.set("fra_www", result.getString("fra_www"));
                schema.set("spr_ext", result.getString("spr_ext"));
                schema.set("spr_onc", result.getString("spr_onc"));
                schema.set("spr_www", result.getString("spr_www"));
                schemas.put(sId, schema);
            }

            // Add the role to schema
            String role = result.getString("role");
            CourseSchema schema = schemas.get(sId);
            String roleList = schema.get("roleList");
            if (roleList == null) {
                roleList = role;
            } else {
                roleList += "," + role;
            }
            schemas.get(sId).set("roleList", roleList);
        }
        close(result);

        List<CourseSchema> response = new ArrayList();
        for (CourseSchema schema : schemas.values()) {
            response.add(schema);
        }
        return response;
    }

    private void removeSchema(CourseSchema schema) throws SQLException {
        String sId = schema.get("id");
        String recordId = schema.getRecordId();
        PreparedStatement delete = prepare("removeSchema",
                "DELETE FROM " + SCHEMA_TABLE + " WHERE schemaId = ?");
        delete.setInt(1, Integer.valueOf(sId));
        delete.executeUpdate();
        removeRoles(recordId, Integer.valueOf(sId));
    }

    private void applySchema(CourseSchema schema) throws SQLException {
        String recordId = schema.getRecordId();
        String course = schema.get("course");
        String year = schema.get("year");
        String semester = schema.get("semester");

        // STEP 1 - INSERT SCHEMA
        PreparedStatement insertSchema = prepare("insertSchema",
                "INSERT INTO " + SCHEMA_TABLE +
                " (recordId, course, courseYear, semester, " +
                "too_ext, too_onc, too_www, " +
                "fra_ext, fra_onc, fra_www, " +
                "spr_ext, spr_onc, spr_www) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        insertSchema.setString(1,  recordId);
        insertSchema.setString(2,  course);
        insertSchema.setString(3,  year);
        insertSchema.setString(4,  semester);
        insertSchema.setString(5,  schema.get("too_ext"));
        insertSchema.setString(6,  schema.get("too_onc"));
        insertSchema.setString(7,  schema.get("too_www"));
        insertSchema.setString(8,  schema.get("fra_ext"));
        insertSchema.setString(9,  schema.get("fra_onc"));
        insertSchema.setString(10, schema.get("fra_www"));
        insertSchema.setString(11, schema.get("spr_ext"));
        insertSchema.setString(12, schema.get("spr_onc"));
        insertSchema.setString(13, schema.get("spr_www"));
        insertSchema.executeUpdate();

        // STEP 2 - GET SCHEMA ID
        int sId;
        PreparedStatement findSid = prepare("findSid",
                "SELECT schemaId FROM " + SCHEMA_TABLE +
                " WHERE recordId = ?" +
                " AND course = ? AND courseYear = ? AND semester = ?");
        findSid.setString(1, recordId);
        findSid.setString(2, course);
        findSid.setString(3, year);
        findSid.setString(4, semester);
        ResultSet result = findSid.executeQuery();
        if (result.next()) {
            sId = result.getInt("schemaId");
        } else {
            throw new SQLException("Unknown error inserting schema.");
        }
        close(result);

        // STEP 3 - GENERATE ROLE LIST
        String roleList = schema.get("roleList");
        String[] roles = StringUtils.split(roleList, ",");
        for (String role : roles) {
            applyRole(recordId, sId, role);
        }
    }

    private List<String> selectRoles(String recordId) throws SQLException {
        List<String> roles = new ArrayList();
        PreparedStatement select = prepare("selectRoles",
                "SELECT DISTINCT role FROM " + ROLE_TABLE +
                " WHERE recordId = ?");
        select.setString(1, recordId);
        ResultSet result = select.executeQuery();

        // Build response
        while (result.next()) {
            roles.add(result.getString("role"));
        }
        return roles;
    }

    private List<String> selectRoles(String recordId, int schemaId)
            throws SQLException {
        List<String> roles = new ArrayList();
        PreparedStatement select = prepare("selectSchemaRoles",
                "SELECT * FROM " + ROLE_TABLE +
                " WHERE recordId = ? AND schemaId = ?");
        select.setString(1, recordId);
        select.setInt(2, schemaId);
        ResultSet result = select.executeQuery();

        // Build response
        while (result.next()) {
            roles.add(result.getString("role"));
        }
        return roles;
    }

    private void removeRoles(String recordId, int schemaId)
            throws SQLException {
        PreparedStatement delete = prepare("removeRole", "DELETE FROM " +
                ROLE_TABLE + " WHERE recordId = ? AND schemaId = ?");
        delete.setString(1, recordId);
        delete.setInt(2, schemaId);
        delete.executeUpdate();
    }

    private void applyRole(String recordId, int schemaId, String role)
            throws SQLException {
        PreparedStatement insert = prepare("insertRole",
                "INSERT INTO " + ROLE_TABLE +
                " (recordId, schemaId, role) VALUES (?, ?, ?)");
        insert.setString(1, recordId);
        insert.setInt(2, schemaId);
        insert.setString(3, role);
        insert.executeUpdate();
    }

    /**
     * Add a new record to the record table.
     *
     * @param recordId The new record
     * @throws SQLException if there were database errors making the change
     */
    private void newRecord(String recordId) throws SQLException {
        PreparedStatement insert = prepare("insertRecord",
                "INSERT INTO " + RECORD_TABLE + " VALUES (?)");
        insert.setString(1, recordId);
        insert.executeUpdate();
    }

    /**
     * Check if the given record has an entry in the record table.
     *
     * @param field The field name.
     * @return boolean flag for if the record exists
     * @throws SQLException if there were database errors during the search
     */
    private boolean checkRecord(String recordId) throws SQLException {
        // Prepare and execute
        PreparedStatement select = prepare("checkRecord",
                "SELECT count(*) as total FROM " + RECORD_TABLE +
                    " WHERE recordId = ?");
        select.setString(1, recordId);
        ResultSet result = select.executeQuery();

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
     * Prepare a statement and return it. The statement will be recorded in the
     * plugin's map of statements to be released at shutdown.
     *
     * @param index The index to file the statement under in the hashmap
     * @param sql The sql statement to prepare
     * @return PreparedStatement The statement that was prepared
     */
    private PreparedStatement prepare(String index, String sql)
            throws SQLException {
        PreparedStatement statement = statements.get(index);
        if (statement == null) {
            // We want blocking actions first,
            // otherwise in order of creation
            statement = conn.prepareStatement(sql);
            statements.put(index, statement);
        }
        return statement;
    }

    /**
     * Attempt to close a ResultSet. Basic wrapper for exception
     * catching and logging
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
     * Attempt to close a Statement. Basic wrapper for exception
     * catching and logging
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
