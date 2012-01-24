/*
 * The Fascinator - Media Repository - DiReCt Messaging Service
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
package com.googlecode.mediarepository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.googlecode.fascinator.api.PluginException;
import com.googlecode.fascinator.api.PluginManager;
import com.googlecode.fascinator.api.access.AccessControlException;
import com.googlecode.fascinator.api.access.AccessControlManager;
import com.googlecode.fascinator.api.access.AccessControlSchema;
import com.googlecode.fascinator.api.indexer.Indexer;
import com.googlecode.fascinator.api.indexer.IndexerException;
import com.googlecode.fascinator.api.storage.DigitalObject;
import com.googlecode.fascinator.api.storage.Payload;
import com.googlecode.fascinator.api.storage.Storage;
import com.googlecode.fascinator.api.storage.StorageException;
import com.googlecode.fascinator.common.messaging.GenericListener;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.JsonSimpleConfig;
import com.googlecode.fascinator.common.storage.StorageUtils;

/**
 * A message processing class for both incoming and outgoing messages to the
 * DiReCt server. Messaging framework adapted from the RenderQueueConsumer.
 *
 * @author Greg Pendlebury
 */
public class DirectMessageService implements GenericListener {

    /** The ID of this queue in the service loader */
    public static final String LISTENER_ID = "DirectMessageService";

    /** Message queue of internal messages coming to this object */
    public static final String MESSAGE_QUEUE = "direct";

    /** Message queue of outgoing API to send to DiReCt */
    public static final String DIRECT_QUEUE = "toDirect";

    /** Logging */
    private Logger log = LoggerFactory.getLogger(DirectMessageService.class);

    /** JSON configuration */
    private JsonSimple globalConfig;

    /** JMS connection */
    private Connection connection;

    /** JMS Session */
    private Session session;

    /** Message Consumer instance */
    private MessageConsumer consumer;

    /** Message Producer instance */
    private MessageProducer producer;

    /** Indexer object */
    private Indexer indexer;

    /** Storage API */
    private Storage storage;

    /** Default portal */
    private String defaultPortal;

    /** Thread reference */
    private Thread thread;

    /** Access Control Manager */
    private AccessControlManager acmSecurity;

    /**
     * Constructor required by ServiceLoader. Be sure to use init()
     *
     */
    public DirectMessageService() {
        thread = new Thread(this, LISTENER_ID);
    }

    /**
     * Start thread running
     *
     */
    @Override
    public void run() {
        try {
            log.info("Starting DiReCt message service: '{}'", MESSAGE_QUEUE);

            // Get a connection to the broker
            String brokerUrl = globalConfig.getString(
                    ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL,
                    "messaging", "url");
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                    brokerUrl);
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Incoming messages
            consumer = session.createConsumer(session
                    .createQueue(MESSAGE_QUEUE));
            consumer.setMessageListener(this);

            // Outgoing messages
            producer = session
                    .createProducer(session.createQueue(DIRECT_QUEUE));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            connection.start();
        } catch (JMSException ex) {
            log.error("Error starting message thread!", ex);
        }
    }

    /**
     * Initialization method
     *
     * @param config Configuration to use
     * @throws IOException if the configuration file not found
     */
    @Override
    public void init(JsonSimpleConfig config) throws Exception {
        try {
            thread.setName(LISTENER_ID);
            globalConfig = new JsonSimple();
            File sysFile = JsonSimpleConfig.getSystemFile();
            // Indexer
            indexer = PluginManager.getIndexer(globalConfig.getString("solr",
                    "indexer", "type"));
            indexer.init(sysFile);
            // Storage
            storage = PluginManager.getStorage(globalConfig.getString(
                    "file-system", "storage", "type"));
            storage.init(sysFile);
            // Access Control
            acmSecurity = PluginManager.getAccessManager("accessmanager");
            acmSecurity.init(sysFile);
            // TODO - "default" should be : PortalManager.DEFAULT_PORTAL_NAME
            // but not available with current dependency arrangement
            defaultPortal = globalConfig.getString("default", "portal", "defaultView");
        } catch (IOException ioe) {
            log.error("Failed to read configuration: {}", ioe.getMessage());
            throw ioe;
        } catch (PluginException pe) {
            log.error("Failed to initialise plugin: {}", pe.getMessage());
            throw pe;
        }
    }

    /**
     * Return the ID string for this listener
     *
     * @returns String ID of this class.
     */
    @Override
    public String getId() {
        return LISTENER_ID;
    }

    /**
     * Start the message listener
     *
     * @throws Exception if an error occurred starting the JMS connection
     */
    @Override
    public void start() throws Exception {
        thread.start();
    }

    /**
     * Stop the Render Queue Consumer. Including stopping the storage and
     * indexer
     */
    @Override
    public void stop() throws Exception {
        log.info("Stopping DiReCt message service...");
        if (indexer != null) {
            try {
                indexer.shutdown();
            } catch (PluginException pe) {
                log.error("Failed to shutdown indexer: {}", pe.getMessage());
                throw pe;
            }
        }
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (PluginException pe) {
                log.error("Failed to shutdown storage: {}", pe.getMessage());
                throw pe;
            }
        }
        if (producer != null) {
            try {
                producer.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close producer: {}", jmse.getMessage());
            }
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close consumer: {}", jmse.getMessage());
                throw jmse;
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close consumer session: {}", jmse);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close connection: {}", jmse);
            }
        }
        if (acmSecurity != null) {
            try {
                acmSecurity.shutdown();
            } catch (PluginException ex) {
                log.warn("Failed shutting down Access Control Manager: {}", ex);
            }
        }
    }

    /**
     * Callback function for incoming messages.
     *
     * @param message The incoming message
     */
    @Override
    public void onMessage(Message message) {
        MDC.put("name", LISTENER_ID);
        try {
            String text = ((TextMessage) message).getText();
            // Verify the json is valid
            JsonSimpleConfig msgJson = new JsonSimpleConfig(text);
            String messageType = msgJson.getString(null, "messageType");
            if (messageType != null
                    && messageType.equals(DirectIncomingApi.EXTERNAL_QUEUE)) {
                // Messages coming back from DiReCt
                log.debug("Incoming message from DiReCt : '{}'", text);
                if (!processDiReCtMessage(msgJson)) {
                    log.error("Failed processing DiReCt Message: {}", text);
                }
            } else {
                // Messages coming from the workflow system
                // Send it to DiReCt
                String newText = prepareNewDiReCtSubmission(msgJson);
                if (newText != null) {
                    log.debug("Sending message to DiReCt : '{}'", newText);
                    producer.send(session.createTextMessage(newText));
                }
            }
        } catch (JMSException jmse) {
            log.error("Failed to receive message: {}", jmse.getMessage());
        } catch (IOException ioe) {
            log.error("Failed to parse message: {}", ioe.getMessage());
        } catch (Exception ex) {
            log.error("Unknown error during message processing!", ex);
        }
    }

    /**
     * Prepare a message string to send to DiReCt. Maps internal data fields to
     * the agreed formats for DiReCt.
     *
     * @param incoming The incoming message as a parsed JSON object
     * @return String The text of the outgoing message.
     * @throws IOException
     */
    private String prepareNewDiReCtSubmission(JsonSimpleConfig incoming)
            throws IOException {
        String oid = incoming.getString(null, "oid");
        JsonSimpleConfig indexData;
        try {
            DigitalObject object = storage.getObject(oid);
            Payload directMetadata = object.getPayload("direct.index");
            indexData = new JsonSimpleConfig(directMetadata.open());
            directMetadata.close();
        } catch (Exception ex) {
            log.error("Error retrieving object metadata: {}", ex.getMessage());
            return null;
        }

        JsonSimpleConfig outgoing = new JsonSimpleConfig();
        outgoing.getJsonObject().put("ma.identifier", oid);
        outgoing.getJsonObject().put("ma.locator",
                globalConfig.getString(null, "urlBase") + defaultPortal + "/detail/" + oid);
        outgoing.getJsonObject().put("ma.title",
                indexData.getString(null, "dc_title"));
        outgoing.getJsonObject().put("ma.description",
                indexData.getString(null, "dc_description"));
        outgoing.getJsonObject().put("ma.format",
                indexData.getString(null, "dc_format"));
        outgoing.getJsonObject().put("ma.creator",
                indexData.getString(null, "dc_creator"));
        outgoing.getJsonObject().put("ma.contributor",
                indexData.getString(null, "dc_contributor"));
        outgoing.getJsonObject().put("ma.location",
                indexData.getString(null, "dc_location"));
        outgoing.getJsonObject().put("ma.language", null);
        outgoing.getJsonObject().put("ma.duration",
                indexData.getString(null, "dc_duration"));
        outgoing.getJsonObject().put("ma.frameSize",
                indexData.getString(null, "dc_size"));
        outgoing.getJsonObject().put("usq.credits",
                indexData.getString(null, "usq_credits"));
        outgoing.getJsonObject().put("dc.available",
                indexData.getString(null, "dc_available"));
        outgoing.getJsonObject().put("usq.data_source", null);
        outgoing.getJsonObject().put("usq.notes",
                indexData.getString(null, "notes"));
        outgoing.getJsonObject().put("usq.course",
                indexData.getString(null, "course_code"));
        outgoing.getJsonObject().put("usq.year",
                indexData.getString(null, "course_year"));
        outgoing.getJsonObject().put("usq.semester",
                indexData.getString(null, "course_semester"));

        outgoing.getJsonObject().put("usq.campus/too_ext",
                indexData.getString(null, "too_ext"));
        outgoing.getJsonObject().put("usq.campus/too_onc",
                indexData.getString(null, "too_onc"));
        outgoing.getJsonObject().put("usq.campus/too_www",
                indexData.getString(null, "too_www"));
        outgoing.getJsonObject().put("usq.campus/fra_ext",
                indexData.getString(null, "fra_ext"));
        outgoing.getJsonObject().put("usq.campus/fra_onc",
                indexData.getString(null, "fra_onc"));
        outgoing.getJsonObject().put("usq.campus/fra_www",
                indexData.getString(null, "fra_www"));
        outgoing.getJsonObject().put("usq.campus/spr_ext",
                indexData.getString(null, "spr_ext"));
        outgoing.getJsonObject().put("usq.campus/spr_onc",
                indexData.getString(null, "spr_onc"));
        outgoing.getJsonObject().put("usq.campus/spr_www",
                indexData.getString(null, "spr_www"));

        return outgoing.toString();
    }

    /**
     * Process and act on messages sent from DiReCt.
     *
     * @param incoming The incoming message as a parsed JSON object
     * @return true if processing was successful, false otherwise
     */
    private boolean processDiReCtMessage(JsonSimpleConfig incoming) {
        String oid = incoming.getString(null, "ma.identifier");
        String key = incoming.getString(null, "usq.direct_item_key");
        String copyright = incoming.getString(null, "usq.copyright");
        JsonSimpleConfig workflow;
        DigitalObject object;

        // Invalid message data
        if (oid == null || key == null) {
            return false;
        }

        // Basic object and metadata retrieval
        try {
            object = storage.getObject(oid);
            Payload wfMeta = object.getPayload("workflow.metadata");
            workflow = new JsonSimpleConfig(wfMeta.open());
            wfMeta.close();
        } catch (Exception ex) {
            log.error("Error retrieving workflow metadata: {}", ex.getMessage());
            return false;
        }

        // Confirmation messages
        if (copyright == null) {
            workflow.getJsonObject().put("directItemKey", key);
            workflow.getJsonObject().put("targetStep", "direct");

            // Completion messages
        } else {
            workflow.getJsonObject().put("directItemKey", key);
            workflow.getJsonObject().put("targetStep", "live");
            // Security - Roles
            updateSecurity(incoming, oid);
            // Security - Individuals
            String exceptions = incoming.getString(null, "usq.exceptions");
            if (exceptions != null) {
                workflow.getJsonObject().put("directSecurityExceptions",
                        exceptions);
            }
            // Copyright Data
            workflow.getJsonObject().put("copyright", copyright);
            if (copyright.equals("true")) {
                String notice = incoming.getString(null, "usq.notice");
                if (notice != null) {
                    workflow.getJsonObject().put("copyrightNotice", notice);
                } else {
                    log.error(
                            "Copyright flag set, but no notice supplied: ({})",
                            oid);
                }
            } else {
                log.info("No copyright applies to this item!");
            }
            // Expiry date
            String expiry = incoming.getString(null, "usq.expiry");
            if (expiry != null) {
                workflow.getJsonObject().put("expiryDate", expiry);
            }
        }

        // Save updated workflow metadata
        try {
            ByteArrayInputStream inStream = new ByteArrayInputStream(workflow
                    .toString().getBytes("UTF-8"));
            StorageUtils.createOrUpdatePayload(object, "workflow.metadata",
                    inStream);
        } catch (StorageException ex) {
            log.error("Error saving workflow data: {}", ex.getMessage());
        } catch (UnsupportedEncodingException ex) {
            log.error("Error decoding workflow data: {}", ex.getMessage());
        }

        try {
            indexer.index(oid);
            indexer.commit();
            return true;

        } catch (IndexerException ex) {
            log.error("Error during index: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Method to segregate all security logic from main processing routine.
     *
     * @param incoming The incoming message as a parsed JSON object
     * @param oid The OID of our object
     */
    private void updateSecurity(JsonSimpleConfig incoming, String oid) {
        // Start by purging old security data
        try {
            acmSecurity.setActivePlugin("course");
            for (AccessControlSchema schema : acmSecurity.getSchemas(oid)) {
                removeSecurity(schema);
            }
        } catch (AccessControlException ex) {
            log.error("Error access Access Control Plugin: {}", ex);
            return;
        }

        // Now add the new security data
        for (JsonSimple security : incoming.getJsonSimpleList("usq.security")) {
            applySecurity(security, oid);
        }
    }

    /**
     * Remove an old security schema from the database.
     *
     * @param schema The old security schema to remove.
     */
    private void removeSecurity(AccessControlSchema schema) {
        acmSecurity.setActivePlugin("course");
        try {
            acmSecurity.removeSchema(schema);
        } catch (AccessControlException ex) {
            log.error("Error removing schema: {}", ex);
        }
    }

    /**
     * Create a new security schema in the database.
     *
     * @param security The new security data to use.
     * @param oid The OID of our object
     */
    private void applySecurity(JsonSimple security, String oid) {
        // Create the schema
        acmSecurity.setActivePlugin("course");
        AccessControlSchema schema = acmSecurity.getEmptySchema();

        // Populate the schema
        schema.setRecordId(oid);
        schema.set("course", security.getString(null, "course"));
        schema.set("year", security.getString(null, "year"));
        schema.set("semester", security.getString(null, "semester"));
        schema.set("roleList", security.getString(null, "roles"));
        schema.set("too_ext", flagTest(security, "too_ext"));
        schema.set("too_onc", flagTest(security, "too_onc"));
        schema.set("too_www", flagTest(security, "too_www"));
        schema.set("fra_ext", flagTest(security, "fra_ext"));
        schema.set("fra_onc", flagTest(security, "fra_onc"));
        schema.set("fra_www", flagTest(security, "fra_www"));
        schema.set("spr_ext", flagTest(security, "spr_ext"));
        schema.set("spr_onc", flagTest(security, "spr_onc"));
        schema.set("spr_www", flagTest(security, "spr_www"));

        // Apply the schema
        try {
            acmSecurity.applySchema(schema);
        } catch (AccessControlException ex) {
            log.error("Error applying security: {}", ex);
        }
    }

    /**
     * A reasonably simple mapping between the flag values used by DiReCt and
     * the security database.
     *
     * "on" => "1" "off" => "0"
     *
     * @param data The new security data to query.
     * @param field The field to check
     */
    private String flagTest(JsonSimple data, String field) {
        if (data == null)
            return "0";
        String value = data.getString(null, "interface/" + field);
        if (value == null)
            return "0";

        if (value.equals("on")) {
            return "1";
        } else {
            return "0";
        }
    }

    /**
     * Sets the priority level for the thread. Used by the OS.
     *
     * @param newPriority The priority level to set the thread at
     */
    @Override
    public void setPriority(int newPriority) {
        if (newPriority >= Thread.MIN_PRIORITY
                && newPriority <= Thread.MAX_PRIORITY) {
            thread.setPriority(newPriority);
        }
    }
}
