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
package com.googlecode.mediarepository;

import com.googlecode.fascinator.api.access.AccessControlException;
import com.googlecode.fascinator.api.access.AccessControlSchema;

import java.io.File;
import java.util.List;
import junit.framework.Assert;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for USQ Course Access Control plugin
 *
 * @author Greg Pendlebury
 */
public class CourseAccessControlTest {
    private CourseAccessControl plugin;
    private AccessControlSchema schema;
    private List<AccessControlSchema> schemas;
    private List<String> roleList;
    private String recordId;
    private String course;
    private String year;
    private String semester;
    private String roles;

    /**
     * Run before EACH test. Instantiate the plugin and prep some simple
     * test data for the schema.
     *
     * @throws Exception if any error occurs
     */
    @Before
    public void setup() throws Exception {
        plugin = new CourseAccessControl();
        File file = new File(
                getClass().getResource("/security_config.json").toURI());
        if (file != null) {
            plugin.init(file);
            recordId = "record";
            course = "ABC1234";
            year = "2010";
            semester = "S3";
            roles = "role";
        }
    }

    /**
     * Run after EACH test. Shutdown the plugin.
     *
     * @throws Exception if any error occurs
     */
    @After
    public void cleanup() throws Exception {
        plugin.shutdown();
    }

    /**
     * Run after ALL tests. Cleanup the database files on disk.
     *
     * @throws Exception if any error occurs
     */
    @AfterClass
    public static void databaseRemove() throws Exception {
        File file = new File(
                System.getProperty("java.io.tmpdir"), "course_unit_test");
        if (file.exists() && file.isDirectory()) {
            delete(file);
        }
    }

    /**
     * Delete a directory and all of its contents.
     *
     * @param dir The directory to delete
     * @return boolean True if successful, False if failed
     * @throws Exception if any error occurs
     */
    public static boolean delete(File dir) {
        File[] files = dir.listFiles();
        boolean flag;
        for (File f : files) {
            flag = f.isDirectory() ? delete(f) : f.delete();
        }
        return dir.delete();
    }

    /**
     * Insert a schema, retrieve it and ensure the metadata is correct.
     * NOTE: This should be the first test performed since it is the only
     * test that looks at the value of the autoinc id field.
     *
     * @throws Exception if any error occurs
     */
    @Test
    public void metadataTest() throws Exception {
        System.out.println("\n======\n  TEST: metadataTest()\n======\n");
        // Create a new schema
        schema = plugin.getEmptySchema();
        recordId = "metadata";
        prepareSchema();
        // Apply and test
        plugin.applySchema(schema);
        assertSchemas(1);
        AccessControlSchema s = schemas.get(0);
        Assert.assertEquals(s.getRecordId(), "metadata");
        // Make sure this is the first test in this class,
        //  or this assertion fill fail
        Assert.assertEquals(s.get("course"), "ABC1234");
        Assert.assertEquals(s.get("year"), "2010");
        Assert.assertEquals(s.get("semester"), "S3");
        Assert.assertEquals(s.get("too_ext"), "1");
        Assert.assertEquals(s.get("too_onc"), "0");
        Assert.assertEquals(s.get("too_www"), "1");
        Assert.assertEquals(s.get("fra_ext"), "0");
        Assert.assertEquals(s.get("fra_onc"), "1");
        Assert.assertEquals(s.get("fra_www"), "0");
        Assert.assertEquals(s.get("spr_ext"), "1");
        Assert.assertEquals(s.get("spr_onc"), "0");
        Assert.assertEquals(s.get("spr_www"), "1");
        Assert.assertEquals(s.get("roleList"), "role");
        // Remove and retest
        plugin.removeSchema(s);
        assertSchemas(0);
    }

    /**
     * Simple insert and delete of a schema
     *
     * @throws Exception if any error occurs
     */
    @Test
    public void schemaTest() throws Exception {
        System.out.println("\n======\n  TEST: schemaTest()\n======\n");
        // Create a new schema
        schema = plugin.getEmptySchema();
        prepareSchema();
        // Apply and test
        plugin.applySchema(schema);
        assertSchemas(1);
        // Remove and retest
        plugin.removeSchema(schemas.get(0));
        assertSchemas(0);
    }

    /**
     * Test a duplicate insertion is caught
     *
     * @throws Exception if any error occurs
     */
    @Test
    public void duplicateTest() throws Exception {
        System.out.println("\n======\n  TEST: duplicateTest()\n======\n");
        // Create a new schema
        recordId = "duplicate";
        schema = plugin.getEmptySchema();
        prepareSchema();
        // Apply and test
        plugin.applySchema(schema);
        assertSchemas(1);
        // Insert again
        try {
            plugin.applySchema(schema);
            Assert.fail("Duplicate insertion not caught!");
        } catch (AccessControlException ex) {
            // All is good
        }
    }

    /**
     * Test a non-existent removal is caught
     *
     * @throws Exception if any error occurs
     */
    @Test
    public void nonExistTest() throws Exception {
        System.out.println("\n======\n  TEST: nonExistTest()\n======\n");
        // Create a new schema
        recordId = "nonExist";
        schema = plugin.getEmptySchema();
        prepareSchema();
        // Try to remove it
        try {
            plugin.removeSchema(schema);
            Assert.fail("Non-Existent schema removal not caught!");
        } catch (AccessControlException ex) {
            // All is good
        }
        // Double-check
        assertSchemas(0);
    }

    /**
     * Ensure that multiple roles per schema are applied correctly.
     *
     * @throws Exception if any error occurs
     */
    @Test
    public void multipleRoles() throws Exception {
        System.out.println("\n======\n  TEST: multipleRoles()\n======\n");
        // Test when empty
        recordId = "multipleRoles";
        roleList = plugin.getRoles(recordId);
        Assert.assertNull(roleList);
        assertSchemas(0);

        // Create a new schema
        roles = "role1,role2";
        schema = plugin.getEmptySchema();
        prepareSchema();
        // Apply and test
        plugin.applySchema(schema);
        assertSchemas(1);
        assertRoles(2);

        // Create a new schema - with overlap
        roles = "role2,role3,role4";
        semester = "S2";
        schema = plugin.getEmptySchema();
        prepareSchema();
        // Apply and test
        plugin.applySchema(schema);
        assertSchemas(2);
        assertRoles(4);
        Assert.assertTrue(roleList.contains("role1"));
        Assert.assertTrue(roleList.contains("role2"));
        Assert.assertTrue(roleList.contains("role3"));
        Assert.assertTrue(roleList.contains("role4"));

        // Find the first schema and remove it
        for (AccessControlSchema s : schemas) {
            String sem = s.get("semester");
            if (sem.equals("S3")) {
                String sId = s.get("id");
                System.out.println("Removing SchemaID: '" + sId + "'\n");
                plugin.removeSchema(s);
            }
        }
        assertSchemas(1);
        assertRoles(3);
        Assert.assertTrue(roleList.contains("role2"));
        Assert.assertTrue(roleList.contains("role3"));
        Assert.assertTrue(roleList.contains("role4"));

        // Remove the last schema - 0 size this time, not null
        plugin.removeSchema(schemas.get(0));
        assertSchemas(0);
        assertRoles(0);
    }

    /**
     * Utility method to assert the expected number of schemas are present
     *
     * @param expected The number of schemas
     * @throws Exception if any error occurs
     */
    private void assertSchemas(int expected) throws Exception {
        schemas = plugin.getSchemas(recordId);
        Assert.assertEquals(expected, schemas.size());
    }

    /**
     * Utility method to assert the expected number of roles are present
     *
     * @param expected The number of roles
     * @throws Exception if any error occurs
     */
    private void assertRoles(int expected) throws Exception {
        roleList = plugin.getRoles(recordId);
        Assert.assertEquals(expected, roleList.size());
    }

    /**
     * Utility method to populate a control schema
     *
     * @throws Exception if any error occurs
     */
    private void prepareSchema() throws Exception {
        schema.setRecordId(recordId);
        schema.set("course", course);
        schema.set("year", year);
        schema.set("semester", semester);
        schema.set("roleList", roles);
        schema.set("too_ext", "1");
        schema.set("too_onc", "0");
        schema.set("too_www", "1");
        schema.set("fra_ext", "0");
        schema.set("fra_onc", "1");
        schema.set("fra_www", "0");
        schema.set("spr_ext", "1");
        schema.set("spr_onc", "0");
        schema.set("spr_www", "1");
    }
}
