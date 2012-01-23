/*
 * The Fascinator - Media Repository - DiReCt Testing
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

import java.io.IOException;

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

import au.edu.usq.fascinator.common.GenericListener;
import au.edu.usq.fascinator.common.JsonSimple;
import au.edu.usq.fascinator.common.JsonSimpleConfig;

/**
 * A class designed to fake the DiReCt part of the media repository workflows.
 * Useful when debugging.
 * 
 * @author Greg Pendlebury
 */
public class DirectTesting implements GenericListener {

    /** The ID of this queue in the service loader */
    public static final String LISTENER_ID = "DirectTesting";

    /** Message queue of outgoing respository messages coming from DiReCt */
    public static final String INC_QUEUE = DirectOutgoingApi.EXTERNAL_QUEUE;

    /** Message queue of incoming respository messages heading to DiReCt */
    public static final String OUT_QUEUE = DirectIncomingApi.EXTERNAL_QUEUE;

    /** Logging */
    private Logger log = LoggerFactory.getLogger(DirectTesting.class);

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

    /** Thread reference */
    private Thread thread;

    /**
     * Constructor required by ServiceLoader. Be sure to use init()
     * 
     */
    public DirectTesting() {
        thread = new Thread(this, LISTENER_ID);
    }

    /**
     * Start thread running
     * 
     */
    @Override
    public void run() {
        try {
            log.info("Starting DiReCt Testing class: '{}'", INC_QUEUE);

            // Get a connection to the broker
            String brokerUrl = globalConfig.getString(
                    ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL,
                    "messaging", "url");
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                    brokerUrl);
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Incoming messages
            consumer = session.createConsumer(session.createQueue(INC_QUEUE));
            consumer.setMessageListener(this);

            // Outgoing messages
            producer = session.createProducer(session.createQueue(OUT_QUEUE));
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
        thread.setName(LISTENER_ID);
        globalConfig = new JsonSimple();
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
     * Stop the listener.
     * 
     * @throws Exception if an error occurred stopping the JMS connection
     * 
     */
    @Override
    public void stop() throws Exception {
        log.info("Stopping DiReCt Testing...");
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
            String newText;

            String text = ((TextMessage) message).getText();
            // Verify the json is valid
            JsonSimpleConfig msgJson = new JsonSimpleConfig(text);
            log.debug("DiReCt Testing : '{}'", text);

            // Sleep 10s and send confirmation to DiReCt
            try {
                Thread.sleep(10000);
            } catch (Exception e) {
            }
            newText = prepareConfirmation(msgJson);
            if (newText != null) {
                producer.send(session.createTextMessage(newText));
            }
            // Sleep 30s and send completion to DiReCt
            try {
                Thread.sleep(30000);
            } catch (Exception e) {
            }
            newText = prepareCompletion(msgJson);
            if (newText != null) {
                producer.send(session.createTextMessage(newText));
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
     * Prepare a fake confirmation of receipt message.
     * 
     * @param incoming The incoming message needing confirmation
     * @return String The confirmation message
     */
    private String prepareConfirmation(JsonSimpleConfig incoming) {
        String oid = incoming.getString(null, "ma.identifier");

        try {
            JsonSimpleConfig outgoing = new JsonSimpleConfig();
            outgoing.getJsonObject().put("ma.identifier", oid);
            outgoing.getJsonObject().put("usq.direct_item_key",
                    "ADIRECTITEMKEY");
            return outgoing.toString();
        } catch (IOException e) {
            log.error("Failed to parse message: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Prepare a fake completion of workflow message.
     * 
     * @param incoming The incoming message that started the workflow
     * @return String The completion message
     * @throws IOException
     */
    private String prepareCompletion(JsonSimpleConfig incoming)
            throws IOException {
        String oid = incoming.getString(null, "ma.identifier");

        JsonSimpleConfig outgoing = new JsonSimpleConfig();
        outgoing.getJsonObject().put("ma.identifier", oid);
        outgoing.getJsonObject().put("usq.direct_item_key", "ADIRECTITEMKEY");
        outgoing.getJsonObject().put("usq.exceptions", "tom,dick,harry");
        outgoing.getJsonObject().put("usq.copyright", "true");
        outgoing.getJsonObject().put("usq.notice",
                "Some sort of copyright notice");
        outgoing.getJsonObject().put("usq.expiry", "2010-12-31T23:59:59.00Z");

        // Add course 1
        JsonSimpleConfig security = new JsonSimpleConfig();
        security.getJsonObject().put("course", "ABC1234");
        security.getJsonObject().put("year", "2010");
        security.getJsonObject().put("semester", "S3");
        security.getJsonObject().put("roles", "class_id1,class_id2");
        security.getJsonObject().put("interface/too_ext", "on");
        security.getJsonObject().put("interface/too_onc", "on");
        security.getJsonObject().put("interface/too_www", "on");
        security.getJsonObject().put("interface/fra_ext", "on");
        security.getJsonObject().put("interface/fra_onc", "on");
        security.getJsonObject().put("interface/fra_www", "on");
        security.getJsonObject().put("interface/spr_ext", "on");
        security.getJsonObject().put("interface/spr_onc", "on");
        security.getJsonObject().put("interface/spr_www", "on");
        String securityJson = security.toString();

        // Add course 2
        security = new JsonSimpleConfig();
        security.getJsonObject().put("course", "ABC1234");
        security.getJsonObject().put("year", "2010");
        security.getJsonObject().put("semester", "S2");
        security.getJsonObject().put("roles", "class_id3,class_id4");
        security.getJsonObject().put("interface/too_ext", "on");
        security.getJsonObject().put("interface/too_onc", "on");
        security.getJsonObject().put("interface/too_www", "on");
        security.getJsonObject().put("interface/fra_ext", "on");
        security.getJsonObject().put("interface/fra_onc", "on");
        security.getJsonObject().put("interface/fra_www", "on");
        security.getJsonObject().put("interface/spr_ext", "on");
        security.getJsonObject().put("interface/spr_onc", "on");
        security.getJsonObject().put("interface/spr_www", "on");
        securityJson = "[" + securityJson + "," + security.toString() + "]";

        // TODO: This is a hack to overcome #656
        // We can't set JSON lists, so we need to hack the string version
        String dummyData = "123DUMMYDATA321";
        outgoing.getJsonObject().put("usq.security", dummyData);
        String json = outgoing.toString();
        json = json.replace("\"" + dummyData + "\"", securityJson);
        try {
            outgoing = new JsonSimpleConfig(json);
        } catch (IOException ex) {
            log.error("Error during JSON parse", ex);
            return null;
        }

        return outgoing.toString();
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
