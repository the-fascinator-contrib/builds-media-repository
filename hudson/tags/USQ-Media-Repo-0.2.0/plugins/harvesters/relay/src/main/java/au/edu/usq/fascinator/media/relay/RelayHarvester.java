/*
 * The Fascinator - Relay Harvester Plugin
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
package au.edu.usq.fascinator.media.relay;

import au.edu.usq.fascinator.api.harvester.HarvesterException;
import au.edu.usq.fascinator.api.storage.DigitalObject;
import au.edu.usq.fascinator.api.storage.Payload;
import au.edu.usq.fascinator.api.storage.PayloadType;
import au.edu.usq.fascinator.api.storage.Storage;
import au.edu.usq.fascinator.api.storage.StorageException;
import au.edu.usq.fascinator.common.harvester.impl.GenericHarvester;
import au.edu.usq.fascinator.common.sax.SafeSAXReader;
import au.edu.usq.fascinator.common.storage.StorageUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harvests publish Camtasia Relay outputs from a specified location on disk.
 * 
 * An stripped down adaptation on the core Fascinator's FileSystem Harvester.
 * 
 * @author Greg Pendlebury
 */
public class RelayHarvester extends GenericHarvester {

    /** Logging */
    private Logger log = LoggerFactory.getLogger(RelayHarvester.class);

    /** Default filter list */
    private static final String DEFAULT_FILTER_PATTERNS = "*.mov|*.avi";

    /** Filter used to target files matching specified patterns */
    private Filter filter;

    /** Stack of queued files to harvest */
    private Stack<File> fileStack;

    /** Whether or not there are more files to harvest */
    private boolean hasMore;

    /** XML parsing **/
    private SafeSAXReader reader;

    /** Wait time */
    private long waitTime;

    /** Archive location */
    private File archive;

    /** Flag whether to archive the extra files from Relay */
    private boolean archiveExtras;

    /** Flag whether to archive the core files from Relay */
    private boolean archiveFiles;

    /**
     * File filter used to target specified files
     */
    private class Filter implements FileFilter {

        /** wildcard patterns of files to target */
        private String[] patterns;

        public Filter(String[] patterns) {
            this.patterns = patterns;
        }

        @Override
        public boolean accept(File path) {
            // We want directories
            if (path.isDirectory()) {
                return true;
            }
            // Or pattern matches
            for (String pattern : patterns) {
                if (FilenameUtils.wildcardMatch(path.getName(), pattern)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Harvester Constructor
     */
    public RelayHarvester() {
        super("relay", "Relay Harvester");
    }

    /**
     * Initialisation of harvester plugin
     * 
     * @throws HarvesterException if fails to initialise
     */
    @Override
    public void init() throws HarvesterException {
        // SAX reader for XML parsing
        reader = new SafeSAXReader();

        // Archives
        String archivePath = getJsonConfig().getString(null, "harvester",
                "relay", "archivePath");
        if (archivePath != null) {
            archive = new File(archivePath);
            if (!archive.exists()) {
                archive.mkdirs();
            }
            if (archive == null || !archive.exists() || !archive.isDirectory()) {
                log.error("Error accessing archive directory: '{}'",
                        archivePath);
            }
            archiveExtras = getJsonConfig().getBoolean(false, "harvester",
                    "relay", "archiveExtras");
            archiveFiles = getJsonConfig().getBoolean(false, "harvester",
                    "relay", "archiveFiles");
        } else {
            archiveExtras = false;
            archiveFiles = false;
        }

        // Check how long to wait on recent files
        waitTime = Long.parseLong(getJsonConfig().getString("300", "harvester",
                "relay", "waitTime"));

        // Setup our filter
        filter = new Filter(getJsonConfig().getString(DEFAULT_FILTER_PATTERNS,
                "harvester", "relay", "filter").split("\\|"));

        // Loop processing variables
        fileStack = new Stack<File>();
        hasMore = false;

        // Find where to start harvesting
        String path = getJsonConfig().getString(null, "harvester", "relay", "filePath");
        if (path != null) {
            File file = new File(path);
            // Check its validity
            if (file != null && file.exists() && file.isDirectory()) {
                fileStack.add(file);
                hasMore = true;
            } else {
                log.error("Invalid path provided: '{}'", path);
            }
        } else {
            log.error("No path provided");
        }
    }

    /**
     * Get the next file due to be harvested
     * 
     * @return The next file to harvest, null if none
     */
    private File getNextFile() {
        if (fileStack.empty()) {
            hasMore = false;
            return null;
        } else {
            hasMore = true;
            return fileStack.pop();
        }
    }

    /**
     * Shutdown the plugin
     * 
     * @throws HarvesterException is there are errors
     */
    @Override
    public void shutdown() throws HarvesterException {
    }

    /**
     * Harvest the next set of files, and return their Object IDs
     * 
     * @return Set<String> The set of object IDs just harvested
     * @throws HarvesterException is there are errors
     */
    @Override
    public Set<String> getObjectIdList() throws HarvesterException {
        Set<String> fileObjectIdList = new HashSet<String>();

        // Make sure we have something to process
        File file = getNextFile();
        if (file == null) {
            return fileObjectIdList;
        }

        // If this is a directory,
        if (file.isDirectory()) {
            File[] children = file.listFiles(filter);
            for (File child : children) {
                fileStack.push(child);
            }

            // It's a file to harvest
        } else {
            // Just make sure it's ready
            if (timeTest(file)) {
                harvestFile(fileObjectIdList, file);
            }
        }

        return fileObjectIdList;
    }

    /**
     * Test the last modified date on the file, and compare to the configured
     * 'waitTime' setting.
     * 
     * @param file: The file to test
     * @return boolean: True if the file is 'old' enough to harvest, False
     * otherwise
     */
    private boolean timeTest(File file) {
        long lastModified = file.lastModified();
        long now = new Date().getTime();
        long age = now - lastModified;
        if (age > waitTime) {
            return true;
        }
        return false;
    }

    /**
     * Harvest a file based on configuration
     * 
     * @param list The set of harvested IDs to add to
     * @param file The file to harvest
     * @throws HarvesterException is there are errors
     */
    private void harvestFile(Set<String> list, File file)
            throws HarvesterException {
        log.debug("=========================");
        log.debug("Harvesting file: '{}'", file.getAbsolutePath());
        Map<String, Object> harvestData = new HashMap();
        List<File> files = new ArrayList();
        List<File> extras = new ArrayList();
        files.add(file);

        // Split the filename into important parts
        String fileName = file.getName();
        String baseName = FilenameUtils.getBaseName(fileName);
        String extension = FilenameUtils.getExtension(fileName);
        int index = fileName.indexOf("Original_Recording");
        if (index == -1) {
            log.error("Source file name invalid: '{}'", file.getAbsolutePath());
            return;
        }
        String prefix = fileName.substring(0, index);
        File parent = file.getParentFile();

        // Look for our metadata
        String metadataName = baseName + ".xml";
        File metadata = new File(parent, metadataName);
        if (!metadata.exists()) {
            log.error("Could not find metadata XML");
            return;
        }

        // Delete the additional outputs Relay delivers
        for (File child : parent.listFiles()) {
            String childName = child.getName();
            if (childName.startsWith(prefix)) {
                // Make sure we don't delete the core files
                if (!childName.equals(metadataName)
                        && !childName.equals(fileName)) {
                    extras.add(child);
                }
            }
        }

        // Parse the XML
        Document doc = null;
        try {
            doc = reader.loadDocument(metadata.getAbsolutePath());
        } catch (DocumentException ex) {
            log.error("Error parsing XML: ", ex);
            return;
        }
        // Presenter details
        Node uNode = doc.selectSingleNode("/presentation/presenter/userName");
        Node eNode = doc.selectSingleNode("/presentation/presenter/email");
        if (uNode == null || eNode == null) {
            log.error("Presenter information missing from XML");
            return;
        }
        String username = uNode.getText();
        String email = eNode.getText();
        // Check for additional outputs,
        //   sometimes the files will come in segments
        List<Node> outputs = doc
                .selectNodes("/presentation/outputFiles/fileList/file");
        for (Node node : outputs) {
            // Need an element object to get the attribute
            Element e = (Element) node;
            String outputName = e.attribute("name").getText();
            if (!outputName.equals(fileName)) {
                File extraFile = new File(parent, outputName);
                files.add(extraFile);
            }
        }
        // Title
        Node tNode = doc.selectSingleNode("/presentation/title");
        String title = "Relay2 recording";
        if (tNode != null) {
            title = tNode.getText();
        }
        // Description
        Node dNode = doc.selectSingleNode("/presentation/description");
        String description = "A Camtasia recording from the Relay2 server";
        if (dNode != null) {
            description = dNode.getText();
        }

        // Compile our data
        log.debug("EXTENSION: '{}'", extension);
        harvestData.put("ext", extension);
        log.debug("OWNER: '{}'", username);
        harvestData.put("owner", username.toLowerCase());
        log.debug("EMAIL: '{}'", email);
        harvestData.put("email", email);
        log.debug("METADATA: '{}'", metadataName);
        harvestData.put("metadata", metadata);
        log.debug("TITLE: '{}'", title);
        harvestData.put("title", title);
        log.debug("DESCRIPTION: '{}'", description);
        harvestData.put("description", description);
        for (File seqFile : files) {
            log.debug("OUTPUT: '{}'", seqFile.getName());
        }
        harvestData.put("outputs", files);

        try {
            String oid = createDigitalObject(harvestData);
            if (oid != null) {
                // Object has been harvested, add to list
                list.add(oid);
                // And cleanup
                archiveFile(oid, metadata);
                for (File corefile : files) {
                    archiveFile(oid, corefile);
                }
                for (File extrafile : extras) {
                    // Segments are in the above loop
                    if (!files.contains(extrafile)) {
                        archiveExtra(oid, extrafile);
                    }
                }
            }
        } catch (StorageException se) {
            log.warn("File not harvested {}: {}", file, se.getMessage());
        }
    }

    /**
     * Archive an 'extra' files if settings dictate, otherwise just delete it.
     * 
     * @param file: The file to archive
     */
    private void archiveExtra(String oid, File file) {
        if (archiveExtras && archive != null) {
            archiveNow(oid, file);
        }
        file.delete();
    }

    /**
     * Archive an 'core' files if settings dictate, otherwise just delete it.
     * 
     * @param file: The file to archive
     */
    private void archiveFile(String oid, File file) {
        if (archiveFiles && archive != null) {
            archiveNow(oid, file);
        }
        file.delete();
    }

    /**
     * Archive any file
     * 
     * @param file: The file to archive
     */
    private void archiveNow(String oid, File file) {
        try {
            File output = new File(archive, oid + "/" + file.getName());
            if (!output.exists()) {
                output.getParentFile().mkdirs();
                output.createNewFile();
            }
            FileInputStream in = new FileInputStream(file);
            FileOutputStream out = new FileOutputStream(output);
            IOUtils.copy(in, out);
            in.close();
            out.close();
        } catch (Exception ex) {
            log.error("Error processing file archive: ", ex);
        }
    }

    /**
     * Check if there are more objects to harvest
     * 
     * @return <code>true</code> if there are more, <code>false</code> otherwise
     */
    @Override
    public boolean hasMoreObjects() {
        return hasMore;
    }

    /**
     * Delete cached references to files which no longer exist and return the
     * set of IDs to delete from the system.
     * 
     * @return Set<String> The set of object IDs deleted
     * @throws HarvesterException is there are errors
     */
    @Override
    public Set<String> getDeletedObjectIdList() throws HarvesterException {
        return new HashSet<String>();
    }

    /**
     * Check if there are more objects to delete
     * 
     * @return <code>true</code> if there are more, <code>false</code> otherwise
     */
    @Override
    public boolean hasMoreDeletedObjects() {
        return false;
    }

    /**
     * Create digital object
     * 
     * @param harvestData A map of all the data required for a harvest
     * @return object id of created digital object
     * @throws HarvesterException if fail to create the object
     * @throws StorageException if fail to save the file to the storage
     */
    private String createDigitalObject(Map<String, Object> harvestData)
            throws HarvesterException, StorageException {
        // Basic preparation
        Storage storage = getStorage();
        String owner = (String) harvestData.get("owner");
        String email = (String) harvestData.get("email");
        String ext = (String) harvestData.get("ext");
        String title = (String) harvestData.get("title");
        String description = (String) harvestData.get("description");
        File metadata = (File) harvestData.get("metadata");
        List<File> files = (List<File>) harvestData.get("outputs");

        // Some simple assessment
        File source = files.get(0);
        String oid = StorageUtils.generateOid(source);
        int segments = files.size();

        // Create/get our object
        DigitalObject object = null;
        try {
            object = storage.createObject(oid);
        } catch (StorageException ex) {
            object = storage.getObject(oid);
        }
        if (object == null) {
            throw new HarvesterException("Unable to create or retrieve object");
        }

        // Store our payloads
        storeFile(object, "source." + ext, source, PayloadType.Source);
        storeFile(object, "relay.xml", metadata, PayloadType.Enrichment);
        if (segments > 0) {
            for (int i = 1; i < segments; i++) {
                storeFile(object, "segment" + i + "." + ext, files.get(i),
                        PayloadType.Enrichment);
            }
        }

        // Update object metadata
        Properties props = object.getMetadata();
        props.setProperty("render-pending", "true");
        props.setProperty("relayOwner", owner);
        props.setProperty("relayEmail", email);
        props.setProperty("relayTitle", title);
        props.setProperty("relayDescription", description);
        if (segments > 1) {
            props.setProperty("mediaSegments", String.valueOf(segments));
        }
        // Reset email process
        props.remove("emailStep");
        object.close();

        return object.getId();
    }

    /**
     * Store a file as a payload on the given object.
     * 
     * @param object: The DigitalObject to store the file inside
     * @param pid: The payload ID to use
     * @param file: The file to store
     * @returns Payload: The created payload
     * @throws HarvesterException: If a failure occurred harvesting the file
     * @throws StorageException: If a failure occurred storing the file
     */
    private Payload storeFile(DigitalObject object, String pid, File file,
            PayloadType type) throws HarvesterException, StorageException {
        try {
            FileInputStream in = new FileInputStream(file);
            Payload p = StorageUtils.createOrUpdatePayload(object, pid, in);
            p.setType(type);
            return p;
        } catch (FileNotFoundException ex) {
            throw new HarvesterException(ex);
        }
    }
}
