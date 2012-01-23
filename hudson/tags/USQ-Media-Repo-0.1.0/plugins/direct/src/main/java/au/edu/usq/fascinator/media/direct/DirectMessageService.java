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
package au.edu.usq.fascinator.media.direct;

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

import au.edu.usq.fascinator.api.PluginException;
import au.edu.usq.fascinator.api.PluginManager;
import au.edu.usq.fascinator.api.access.AccessControlException;
import au.edu.usq.fascinator.api.access.AccessControlManager;
import au.edu.usq.fascinator.api.access.AccessControlSchema;
import au.edu.usq.fascinator.api.indexer.Indexer;
import au.edu.usq.fascinator.api.indexer.IndexerException;
import au.edu.usq.fascinator.api.storage.DigitalObject;
import au.edu.usq.fascinator.api.storage.Payload;
import au.edu.usq.fascinator.api.storage.Storage;
import au.edu.usq.fascinator.api.storage.StorageException;
import au.edu.usq.fascinator.common.GenericListener;
import au.edu.usq.fascinator.common.JsonConfig;
import au.edu.usq.fascinator.common.JsonConfigHelper;
import au.edu.usq.fascinator.common.storage.StorageUtils;

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
    private JsonConfig globalConfig;

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
            String brokerUrl = globalConfig.get("messaging/url",
                    ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL);
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
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
     * @param config
     *            Configuration to use
     * @throws IOException
     *             if the configuration file not found
     */
    @Override
    public void init(JsonConfigHelper config) throws Exception {
        try {
            thread.setName(LISTENER_ID);
            globalConfig = new JsonConfig();
            File sysFile = JsonConfig.getSystemFile();
            // Indexer
            indexer = PluginManager.getIndexer(globalConfig.get("indexer/type",
                    "solr"));
            indexer.init(sysFile);
            // Storage
            storage = PluginManager.getStorage(globalConfig.get("storage/type",
                    "file-system"));
            storage.init(sysFile);
            // Access Control
            acmSecurity = PluginManager.getAccessManager("accessmanager");
            acmSecurity.init(sysFile);
            // TODO - "default" should be : PortalManager.DEFAULT_PORTAL_NAME
            // but not available with current dependency arrangement
            defaultPortal = globalConfig.get("portal/defaultView", "default");
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
     * @throws Exception
     *             if an error occurred starting the JMS connection
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
     * @param message
     *            The incoming message
     */
    @Override
    public void onMessage(Message message) {
        MDC.put("name", LISTENER_ID);
        try {
            String text = ((TextMessage) message).getText();
            // Verify the json is valid
            JsonConfigHelper msgJson = new JsonConfigHelper(text);
            String messageType = msgJson.get("messageType");
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
     * @param incoming
     *            The incoming message as a parsed JSON object
     * @return String The text of the outgoing message.
     */
    private String prepareNewDiReCtSubmission(JsonConfigHelper incoming) {
        String oid = incoming.get("oid");
        JsonConfigHelper indexData;
        try {
            DigitalObject object = storage.getObject(oid);
            Payload directMetadata = object.getPayload("direct.index");
            indexData = new JsonConfigHelper(directMetadata.open());
            directMetadata.close();
        } catch (Exception ex) {
            log.error("Error retrieving object metadata: {}", ex.getMessage());
            return null;
        }

        JsonConfigHelper outgoing = new JsonConfigHelper();
        outgoing.set("ma.identifier", oid);
        outgoing.set("ma.locator", globalConfig.get("urlBase") + defaultPortal
                + "/detail/" + oid);
        outgoing.set("ma.title", indexData.get("dc_title"));
        outgoing.set("ma.description", indexData.get("dc_description"));
        outgoing.set("ma.format", indexData.get("dc_format"));
        outgoing.set("ma.creator", indexData.get("dc_creator"));
        outgoing.set("ma.contributor", indexData.get("dc_contributor"));
        outgoing.set("ma.location", indexData.get("dc_location"));
        outgoing.set("ma.language", null);
        outgoing.set("ma.duration", indexData.get("dc_duration"));
        outgoing.set("ma.frameSize", indexData.get("dc_size"));
        outgoing.set("usq.credits", indexData.get("usq_credits"));
        outgoing.set("dc.available", indexData.get("dc_available"));
        outgoing.set("usq.data_source", null);
        outgoing.set("usq.notes", indexData.get("notes"));
        outgoing.set("usq.course", indexData.get("course_code"));
        outgoing.set("usq.year", indexData.get("course_year"));
        outgoing.set("usq.semester", indexData.get("course_semester"));

        outgoing.set("usq.campus/too_ext", indexData.get("too_ext"));
        outgoing.set("usq.campus/too_onc", indexData.get("too_onc"));
        outgoing.set("usq.campus/too_www", indexData.get("too_www"));
        outgoing.set("usq.campus/fra_ext", indexData.get("fra_ext"));
        outgoing.set("usq.campus/fra_onc", indexData.get("fra_onc"));
        outgoing.set("usq.campus/fra_www", indexData.get("fra_www"));
        outgoing.set("usq.campus/spr_ext", indexData.get("spr_ext"));
        outgoing.set("usq.campus/spr_onc", indexData.get("spr_onc"));
        outgoing.set("usq.campus/spr_www", indexData.get("spr_www"));

        return outgoing.toString();
    }

    /**
     * Process and act on messages sent from DiReCt.
     * 
     * @param incoming
     *            The incoming message as a parsed JSON object
     * @return true if processing was successful, false otherwise
     */
    private boolean processDiReCtMessage(JsonConfigHelper incoming) {
        String oid = incoming.get("ma.identifier");
        String key = incoming.get("usq.direct_item_key");
        String copyright = incoming.get("usq.copyright");
        JsonConfigHelper workflow;
        DigitalObject object;

        // Invalid message data
        if (oid == null || key == null) {
            return false;
        }

        // Basic object and metadata retrieval
        try {
            object = storage.getObject(oid);
            Payload wfMeta = object.getPayload("workflow.metadata");
            workflow = new JsonConfigHelper(wfMeta.open());
            wfMeta.close();
        } catch (Exception ex) {
            log
                    .error("Error retrieving workflow metadata: {}", ex
                            .getMessage());
            return false;
        }

        // Confirmation messages
        if (copyright == null) {
            workflow.set("directItemKey", key);
            workflow.set("targetStep", "direct");

            // Completion messages
        } else {
            workflow.set("directItemKey", key);
            workflow.set("targetStep", "live");
            // Security - Roles
            updateSecurity(incoming, oid);
            // Security - Individuals
            String exceptions = incoming.get("usq.exceptions");
            if (exceptions != null) {
                workflow.set("directSecurityExceptions", exceptions);
            }
            // Copyright Data
            workflow.set("copyright", copyright);
            if (copyright.equals("true")) {
                String notice = incoming.get("usq.notice");
                if (notice != null) {
                    workflow.set("copyrightNotice", notice);
                } else {
                    log
                            .error("Copyright flag set, but no notice supplied: ({})",
                                    oid);
                }
            } else {
                log.info("No copyright applies to this item!");
            }
            // Expiry date
            String expiry = incoming.get("usq.expiry");
            if (expiry != null) {
                workflow.set("expiryDate", expiry);
            }
        }

        // Save updated workflow metadata
        try {
            ByteArrayInputStream inStream = new ByteArrayInputStream(workflow
                    .toString().getBytes("UTF-8"));
            StorageUtils.createOrUpdatePayload(object,
                    "workflow.metadata",
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
     * @param incoming
     *            The incoming message as a parsed JSON object
     * @param oid
     *            The OID of our object
     */
    private void updateSecurity(JsonConfigHelper incoming, String oid) {
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
        for (JsonConfigHelper security : incoming.getJsonList("usq.security")) {
            applySecurity(security, oid);
        }
    }

    /**
     * Remove an old security schema from the database.
     * 
     * @param schema
     *            The old security schema to remove.
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
     * @param security
     *            The new security data to use.
     * @param oid
     *            The OID of our object
     */
    private void applySecurity(JsonConfigHelper security, String oid) {
        // Create the schema
        acmSecurity.setActivePlugin("course");
        AccessControlSchema schema = acmSecurity.getEmptySchema();

        // Populate the schema
        schema.setRecordId(oid);
        schema.set("course", security.get("course"));
        schema.set("year", security.get("year"));
        schema.set("semester", security.get("semester"));
        schema.set("roleList", security.get("roles"));
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
     * @param data
     *            The new security data to query.
     * @param field
     *            The field to check
     */
    private String flagTest(JsonConfigHelper data, String field) {
        if (data == null) return "0";
        String value = data.get("interface/" + field);
        if (value == null) return "0";

        if (value.equals("on")) {
            return "1";
        } else {
            return "0";
        }
    }

    /**
     * Sets the priority level for the thread. Used by the OS.
     * 
     * @param newPriority
     *            The priority level to set the thread at
     */
    @Override
    public void setPriority(int newPriority) {
        if (newPriority >= Thread.MIN_PRIORITY
                && newPriority <= Thread.MAX_PRIORITY) {
            thread.setPriority(newPriority);
        }
    }
}
